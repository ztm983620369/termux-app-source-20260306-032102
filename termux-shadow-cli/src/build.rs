use std::env;
use std::fs::{self, File};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, bail};
use serde::Serialize;

use crate::cache;
use crate::cli::{BuildArgs, DependencyPolicy, PublishArgs, UpgradeArgs};
use crate::config::{PluginConfig, valid_version_name};
use crate::context::{AppContext, BuildEnvironment};
use crate::control;
use crate::dependency;
use crate::doctor;
use crate::errors::GradleFailure;
use crate::fsutil::{remove_dir_if_exists, sha256_file, sha256_paths, write_atomic};
use crate::history;
use crate::status::{find_receipt, read_registry, wait_for_receipt};

const FINGERPRINT_INPUTS: [&str; 5] = [
    "shadow-plugin.properties",
    "build.gradle",
    "settings.gradle",
    "plugin-app/build.gradle",
    "plugin-app/src/main/AndroidManifest.xml",
];

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ArtifactOutput {
    pub path: String,
    pub sha256: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct BuildOutput {
    pub ok: bool,
    pub action: &'static str,
    pub status: &'static str,
    pub project: String,
    pub plugin_id: String,
    pub version_code: u64,
    pub version_name: String,
    pub source_fingerprint: String,
    pub toolchain_fingerprint: String,
    pub input_fingerprint: String,
    pub artifacts: Vec<ArtifactOutput>,
    pub cache: &'static str,
    pub gradle: Option<GradleOutcome>,
    pub timings: BuildTimings,
    pub receipt: Option<crate::status::PublishReceipt>,
    pub registration_confirmed: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct BuildTimings {
    pub build_ms: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub publish_ms: Option<u64>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct StoredPublishReceipt<'a> {
    schema_version: u32,
    plugin_id: &'a str,
    version_code: u64,
    version_name: &'a str,
    sha256: &'a str,
    file_name: &'a str,
    resource_package_id: &'a str,
    published_at_epoch_ms: u64,
    runtime_status: &'static str,
    runtime_proven: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BuildWarning {
    pub code: String,
    pub message: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GradleOutcome {
    pub duration_ms: u64,
    pub daemon: &'static str,
    pub warnings: Vec<BuildWarning>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ValidationOutcome {
    pub cache: &'static str,
    pub artifact: String,
    pub sha256: String,
    pub gradle: Option<GradleOutcome>,
}

pub fn run_build(context: &AppContext, args: BuildArgs) -> Result<()> {
    execute_build(context, args, false, false, 0, true).map(|_| ())
}

pub fn run_publish(context: &AppContext, args: PublishArgs) -> Result<()> {
    execute_publish(context, args, true).map(|_| ())
}

pub(crate) fn execute_publish(
    context: &AppContext,
    args: PublishArgs,
    emit: bool,
) -> Result<BuildOutput> {
    let started = Instant::now();
    if !context.is_real_termux_home() {
        bail!(
            "publishing is allowed only inside the com.termux home, got {}",
            context.termux_home.display()
        );
    }
    let project = context.project()?;
    let config = PluginConfig::load(&project.join("shadow-plugin.properties"))?;
    dependency::require_valid_lock(&project)?;
    let requested_version_code = args
        .build
        .version_code
        .unwrap_or(config.default_version_code);
    let requested_version_name = args
        .build
        .version_name
        .as_deref()
        .unwrap_or(&config.default_version_name);
    let highest = highest_committed_version_code(context, &config)?;
    if args.build.version_code.is_none() && args.build.version_name.is_none() && highest.is_some() {
        bail!(
            "VERSION_REQUIRED: this plugin has published history; pass an explicit version, use upgrade, or use deploy --bump"
        );
    }
    let publish_context = context.with_dependency_policy(DependencyPolicy::Offline, false);
    let environment = publish_context.build_environment_for_project(&project)?;
    let fingerprint = cache::input_fingerprint(
        &project,
        &environment,
        requested_version_code,
        requested_version_name,
    )?;
    if !args.build.fresh
        && let Some(hit) = cache::lookup(
            &project,
            &fingerprint,
            requested_version_code,
            requested_version_name,
            true,
        )?
        && registered_sha(context, &config.plugin_id, &hit.sha256)?
    {
        crate::runtime_artifacts::reconcile_project(context, &config.plugin_id)?;
        let source_fingerprint = cache::source_fingerprint(&project, &environment)?;
        let toolchain_fingerprint = cache::toolchain_fingerprint(&environment)?;
        let receipt = find_receipt(&project, &context.shadow_home, &config.plugin_id)?
            .filter(|receipt| receipt.sha256 == hit.sha256);
        let output = BuildOutput {
            ok: true,
            action: "publish",
            status: "ALREADY_PUBLISHED",
            project: project.display().to_string(),
            plugin_id: config.plugin_id.clone(),
            version_code: requested_version_code,
            version_name: requested_version_name.to_owned(),
            source_fingerprint,
            toolchain_fingerprint,
            input_fingerprint: fingerprint,
            artifacts: vec![ArtifactOutput {
                path: hit.artifact.display().to_string(),
                sha256: hit.sha256.clone(),
            }],
            cache: "HIT",
            gradle: None,
            timings: BuildTimings {
                build_ms: elapsed_ms(started),
                publish_ms: Some(0),
            },
            receipt,
            registration_confirmed: true,
        };
        if emit && context.json {
            println!(
                "{}",
                serde_json::to_string_pretty(&serde_json::json!({
                    "ok": true,
                    "action": "publish",
                    "status": "ALREADY_PUBLISHED",
                    "project": project.display().to_string(),
                    "pluginId": config.plugin_id,
                    "versionCode": requested_version_code,
                    "versionName": requested_version_name,
                    "artifact": {
                        "path": hit.artifact.display().to_string(),
                        "sha256": hit.sha256,
                    },
                    "cache": "HIT",
                    "gradle": null,
                    "registrationConfirmed": true,
                    "stateChanged": false,
                }))?
            );
        } else if emit {
            println!("publish: ALREADY_PUBLISHED  {}", config.plugin_id);
            println!("  sha256={}", hit.sha256);
            println!("  Gradle: not started; Host already owns this exact artifact");
        }
        return Ok(output);
    }
    enforce_publish_version(requested_version_code, highest, args.allow_downgrade)?;
    execute_build(context, args.build, true, !args.no_wait, args.timeout, emit)
}

fn enforce_publish_version(
    requested_version_code: u64,
    highest: Option<u64>,
    allow_downgrade: bool,
) -> Result<()> {
    if allow_downgrade {
        return Ok(());
    }
    if let Some(highest) = highest {
        if requested_version_code < highest {
            bail!(
                "DOWNGRADE_BLOCKED: publish versionCode {requested_version_code} is lower than the highest registered versionCode {highest}; explicitly opt in with --allow-downgrade only for an intentional rollback artifact"
            );
        }
        if requested_version_code == highest {
            bail!(
                "VERSION_NOT_INCREASING: publish versionCode {requested_version_code} must be greater than the highest registered versionCode {highest}; use deploy --bump or upgrade"
            );
        }
    }
    Ok(())
}

fn registered_sha(context: &AppContext, plugin_id: &str, sha256: &str) -> Result<bool> {
    if !context.shadow_home.join("reports/registry.json").is_file() {
        return Ok(false);
    }
    let registry = read_registry(&context.shadow_home)?;
    Ok(registry
        .plugins
        .iter()
        .find(|plugin| plugin.plugin_id == plugin_id)
        .is_some_and(|plugin| {
            plugin.versions.iter().any(|version| {
                version.bundle_sha256 == sha256
                    && version.state != "REMOVED"
                    && version.state != "QUARANTINED"
                    && version.state != "FAILED"
            })
        }))
}

pub fn run_upgrade(context: &AppContext, args: UpgradeArgs) -> Result<()> {
    let project = context.project()?;
    let config = PluginConfig::load(&project.join("shadow-plugin.properties"))?;
    let highest =
        highest_committed_version_code(context, &config)?.unwrap_or(config.default_version_code);
    if args.version_code <= highest {
        bail!(
            "VERSION_NOT_INCREASING: upgrade versionCode must be greater than the highest known versionCode {highest}, got {}",
            args.version_code
        );
    }
    dependency::require_valid_lock(&project)?;
    execute_build(
        context,
        BuildArgs {
            version_code: Some(args.version_code),
            version_name: Some(args.version_name),
            fresh: false,
        },
        true,
        true,
        args.timeout,
        true,
    )
    .map(|_| ())
}

pub(crate) fn highest_committed_version_code(
    context: &AppContext,
    config: &PluginConfig,
) -> Result<Option<u64>> {
    let project = context.project()?;
    let registry_highest = if context.shadow_home.join("reports/registry.json").is_file() {
        read_registry(&context.shadow_home)?
            .plugins
            .iter()
            .find(|plugin| plugin.plugin_id == config.plugin_id)
            .and_then(|plugin| {
                plugin
                    .versions
                    .iter()
                    .filter_map(|version| {
                        version
                            .manifest
                            .as_ref()
                            .and_then(|manifest| manifest.version_code)
                    })
                    .max()
            })
    } else {
        None
    };
    let receipt_highest = find_receipt(&project, &context.shadow_home, &config.plugin_id)?
        .and_then(|receipt| receipt.version_code);
    let history_highest = history::highest_version_code(&context.shadow_home, &config.plugin_id)?;
    // A validated cache entry is reusable work, not a committed release. Only state that
    // crossed the publish boundary may advance automatic version allocation.
    Ok([registry_highest, receipt_highest, history_highest]
        .into_iter()
        .flatten()
        .max())
}

pub fn run_clean(context: &AppContext) -> Result<()> {
    let project = context.project()?;
    for relative in [".gradle", "build", "dist", "plugin-app/build"] {
        remove_dir_if_exists(&project.join(relative))?;
    }
    let local_properties = project.join("local.properties");
    if local_properties.exists() {
        fs::remove_file(&local_properties)
            .with_context(|| format!("remove {}", local_properties.display()))?;
    }
    if context.json {
        println!(
            "{}",
            serde_json::to_string_pretty(&serde_json::json!({
                "ok": true,
                "action": "clean",
                "project": project.display().to_string(),
                "status": "OK",
                "stateChanged": true,
            }))?
        );
    } else {
        println!("Cleaned generated outputs: {}", project.display());
    }
    Ok(())
}

pub fn run_stop(context: &AppContext) -> Result<()> {
    let project = context.project()?;
    let environment = context.build_environment_for_project(&project)?;
    run_gradle(context, &project, &environment, &["--stop".to_owned()])?;
    if context.json {
        println!(
            "{}",
            serde_json::to_string_pretty(&serde_json::json!({
                "ok": true,
                "action": "stop",
                "status": "OK",
                "stateChanged": false,
            }))?
        );
    } else {
        println!("Stopped reusable Gradle workers.");
    }
    Ok(())
}

pub fn validate_package(
    context: &AppContext,
    project: &Path,
    config: &PluginConfig,
    fresh: bool,
) -> Result<ValidationOutcome> {
    dependency::ensure_lock(context, project)?;
    let environment = context.build_environment_for_project(project)?;
    dependency::prepare_cache_layers(&environment)?;
    write_local_properties(project, &environment)?;
    let fingerprint = cache::input_fingerprint(
        project,
        &environment,
        config.default_version_code,
        &config.default_version_name,
    )?;
    if !fresh
        && let Some(hit) = cache::lookup(
            project,
            &fingerprint,
            config.default_version_code,
            &config.default_version_name,
            false,
        )?
    {
        return Ok(ValidationOutcome {
            cache: "HIT",
            artifact: hit.artifact.display().to_string(),
            sha256: hit.sha256,
            gradle: None,
        });
    }
    let gradle = run_gradle(
        context,
        project,
        &environment,
        &["validateShadowPluginDebug".to_owned()],
    )?;
    let artifact = project.join(format!(
        "build/package/debug/{}-{}.shadowpkg",
        config.bundle_base_name, config.default_version_name
    ));
    if !artifact.is_file() {
        bail!(
            "Gradle validation succeeded but package is missing: {}",
            artifact.display()
        );
    }
    let sha256 = sha256_file(&artifact)?;
    cache::store(
        project,
        fingerprint,
        config.default_version_code,
        config.default_version_name.clone(),
        &artifact,
        sha256.clone(),
    )?;
    Ok(ValidationOutcome {
        cache: if fresh { "FRESH" } else { "MISS" },
        artifact: artifact.display().to_string(),
        sha256,
        gradle: Some(gradle),
    })
}

pub(crate) fn execute_build(
    context: &AppContext,
    args: BuildArgs,
    publish: bool,
    wait: bool,
    timeout_seconds: u64,
    emit: bool,
) -> Result<BuildOutput> {
    validate_version_args(&args)?;
    if publish && !context.is_real_termux_home() {
        bail!(
            "publishing is allowed only inside the com.termux home, got {}",
            context.termux_home.display()
        );
    }
    let project = context.project()?;
    doctor::validate_for_project(context, &project, false, publish)?;
    let config = PluginConfig::load(&project.join("shadow-plugin.properties"))?;
    if publish {
        dependency::require_valid_lock(&project)?;
    } else {
        dependency::ensure_lock(context, &project)?;
    }
    // A published artifact is always rebuilt/validated against the committed lock and an
    // offline cache, even when the caller selected cache-first or online for development.
    let gradle_context = if publish {
        context.with_dependency_policy(DependencyPolicy::Offline, false)
    } else {
        context.clone()
    };
    let version_code = args.version_code.unwrap_or(config.default_version_code);
    let version_name = args
        .version_name
        .clone()
        .unwrap_or_else(|| config.default_version_name.clone());
    if emit && !context.json {
        println!(
            "Shadow plugin {}",
            if publish { "publish" } else { "build" }
        );
        println!("  project: {}", project.display());
        println!("  pluginId: {}", config.plugin_id);
        println!("  version: {version_name} ({version_code})");
        println!("  plugin preflight: PASS");
    }
    let environment = gradle_context.build_environment_for_project(&project)?;
    dependency::prepare_cache_layers(&environment)?;
    if publish {
        dependency::verify_locked_artifacts(&gradle_context, &project)?;
    }
    invalidate_if_tooling_changed(&project, context.json || !emit)?;
    write_local_properties(&project, &environment)?;
    let fingerprint =
        cache::input_fingerprint(&project, &environment, version_code, &version_name)?;
    let source_fingerprint = cache::source_fingerprint(&project, &environment)?;
    let toolchain_fingerprint = cache::toolchain_fingerprint(&environment)?;

    let build_started = Instant::now();
    let reusable = if !args.fresh {
        cache::lookup(&project, &fingerprint, version_code, &version_name, true)?
    } else {
        None
    };
    let cached = reusable.clone();

    let (artifacts, cache_status, gradle) = if let Some(hit) = cached {
        persist_tooling_fingerprint(&project)?;
        (
            vec![ArtifactOutput {
                path: hit.artifact.display().to_string(),
                sha256: hit.sha256,
            }],
            cache_status(publish, args.fresh, true),
            None,
        )
    } else {
        let cache_status = cache_status(publish, args.fresh, false);
        if emit && !context.json {
            println!("\nPackage build");
            println!("  native cache: {cache_status}");
            println!(
                "  Gradle: running with {} dependency policy{}...",
                gradle_context.dependency_policy.as_str(),
                if gradle_context.allow_network {
                    " (network allowed)"
                } else {
                    ""
                }
            );
            io::stdout().flush().ok();
        }

        let mut gradle_args = Vec::new();
        if let Some(version_code) = args.version_code {
            gradle_args.push(format!("-PshadowPluginVersionCode={version_code}"));
        }
        if let Some(version_name) = args.version_name {
            gradle_args.push(format!("-PshadowPluginVersionName={version_name}"));
        }
        gradle_args.push("copyShadowPluginDebugToDist".to_owned());
        let gradle = run_gradle(&gradle_context, &project, &environment, &gradle_args)?;
        persist_tooling_fingerprint(&project)?;

        let artifact_paths = dist_artifacts(&project)?;
        if artifact_paths.is_empty() {
            bail!("Gradle succeeded but dist contains no .shadowpkg artifact");
        }
        let mut artifacts = Vec::with_capacity(artifact_paths.len());
        for artifact in artifact_paths {
            let sha256 = sha256_file(&artifact)?;
            cache::store(
                &project,
                fingerprint.clone(),
                version_code,
                version_name.clone(),
                &artifact,
                sha256.clone(),
            )?;
            artifacts.push(ArtifactOutput {
                path: artifact.display().to_string(),
                sha256,
            });
        }
        (artifacts, cache_status, Some(gradle))
    };

    if emit && !context.json {
        if cache_status == "HIT" || cache_status == "VALIDATED_REUSE" {
            println!("\nPackage build");
            println!("  native cache: {cache_status}");
            println!("  Gradle: not started");
        } else if let Some(outcome) = &gradle {
            print_gradle_summary(outcome, "  ");
        }
    }
    let build_duration_ms = elapsed_ms(build_started);

    let mut receipt = None;
    let mut registration_confirmed = false;
    let mut publish_duration_ms = None;
    if publish {
        let publish_started = Instant::now();
        let artifact = artifacts
            .first()
            .context("validated publish artifact is missing")?;
        let published = publish_validated_artifact(
            context,
            &project,
            &config,
            version_code,
            &version_name,
            &PathBuf::from(&artifact.path),
            &artifact.sha256,
        )?;
        if let Err(error) = control::try_refresh(context)
            && emit
        {
            eprintln!(
                "[WARN] Host control refresh was unavailable ({error}); waiting for the inbox observer"
            );
        }
        if emit && !context.json {
            println!(
                "Publish receipt: {}/dist/last-published.json",
                project.display()
            );
        }
        if wait {
            if emit && !context.json {
                println!("Waiting for Host registration: {}", published.sha256);
            }
            wait_for_receipt(
                &context.shadow_home,
                &published,
                Duration::from_secs(timeout_seconds),
            )?;
            registration_confirmed = true;
            if emit && !context.json {
                println!("Registration confirmed: {}", published.sha256);
            }
        }
        receipt = Some(published);
        publish_duration_ms = Some(elapsed_ms(publish_started));
    } else if emit && !context.json {
        println!("Validated artifacts:");
        for artifact in &artifacts {
            println!("  {}", artifact.path);
            println!("    sha256={}", artifact.sha256);
        }
    }

    let output = BuildOutput {
        ok: true,
        action: if publish { "publish" } else { "build" },
        status: if publish && registration_confirmed {
            "REGISTERED"
        } else if publish {
            "PUBLISHED"
        } else {
            "VALIDATED"
        },
        project: project.display().to_string(),
        plugin_id: config.plugin_id,
        version_code,
        version_name,
        source_fingerprint,
        toolchain_fingerprint,
        input_fingerprint: fingerprint,
        artifacts,
        cache: cache_status,
        gradle,
        timings: BuildTimings {
            build_ms: build_duration_ms,
            publish_ms: publish_duration_ms,
        },
        receipt,
        registration_confirmed,
    };
    if context.json && emit {
        println!("{}", serde_json::to_string_pretty(&output)?);
    }
    Ok(output)
}

fn elapsed_ms(started: Instant) -> u64 {
    started.elapsed().as_millis().try_into().unwrap_or(u64::MAX)
}

fn cache_status(publish: bool, fresh: bool, hit: bool) -> &'static str {
    if hit && publish {
        "VALIDATED_REUSE"
    } else if hit {
        "HIT"
    } else if fresh {
        "FRESH"
    } else {
        "MISS"
    }
}

fn publish_validated_artifact(
    context: &AppContext,
    project: &Path,
    config: &PluginConfig,
    version_code: u64,
    version_name: &str,
    artifact: &Path,
    expected_sha256: &str,
) -> Result<crate::status::PublishReceipt> {
    if !context.is_real_termux_home() {
        bail!("native publishing is restricted to the real com.termux home");
    }
    publish_validated_to(
        project,
        &context.shadow_home,
        config,
        version_code,
        version_name,
        artifact,
        expected_sha256,
    )
}

fn publish_validated_to(
    project: &Path,
    shadow_home: &Path,
    config: &PluginConfig,
    version_code: u64,
    version_name: &str,
    artifact: &Path,
    expected_sha256: &str,
) -> Result<crate::status::PublishReceipt> {
    if !artifact.is_file() {
        bail!("validated artifact is missing: {}", artifact.display());
    }
    let artifact = fs::canonicalize(artifact)?;
    let project = fs::canonicalize(project)?;
    if !artifact.starts_with(project.join("dist")) {
        bail!(
            "validated artifact is outside the project dist directory: {}",
            artifact.display()
        );
    }
    let actual_sha256 = sha256_file(&artifact)?;
    if actual_sha256 != expected_sha256 {
        bail!(
            "PACKAGE_VALIDATION_FAILED: validated artifact SHA changed before publish; expected={expected_sha256} actual={actual_sha256}"
        );
    }
    let inbox = shadow_home.join("inbox");
    create_private_directory(shadow_home)?;
    create_private_directory(&inbox)?;
    let file_name = format!(
        "{}-{}-{}.shadowpkg",
        config.plugin_id,
        version_name,
        &actual_sha256[..16]
    );
    let target = inbox.join(&file_name);
    if target.exists() {
        if !target.is_file() || sha256_file(&target)? != actual_sha256 {
            bail!(
                "PUBLISH_FAILED: inbox target already exists with different content: {}",
                target.display()
            );
        }
    } else {
        let temporary = inbox.join(format!(
            ".{file_name}.{}.{}.part",
            std::process::id(),
            now_millis()
        ));
        fs::copy(&artifact, &temporary).with_context(|| {
            format!(
                "copy validated artifact {} to {}",
                artifact.display(),
                temporary.display()
            )
        })?;
        crate::fsutil::set_private_permissions(&temporary)?;
        File::options().write(true).open(&temporary)?.sync_all()?;
        fs::rename(&temporary, &target)
            .with_context(|| format!("atomically publish {}", target.display()))?;
        crate::fsutil::set_private_permissions(&target)?;
        sync_directory(&inbox)?;
    }
    let receipt = crate::status::PublishReceipt {
        plugin_id: config.plugin_id.clone(),
        sha256: actual_sha256,
        file_name,
        version_code: Some(version_code),
        version_name: Some(version_name.to_owned()),
        resource_package_id: Some(config.resource_package_id.clone()),
    };
    let stored = StoredPublishReceipt {
        schema_version: 2,
        plugin_id: &receipt.plugin_id,
        version_code,
        version_name,
        sha256: &receipt.sha256,
        file_name: &receipt.file_name,
        resource_package_id: receipt.resource_package_id.as_deref().unwrap_or(""),
        published_at_epoch_ms: now_millis(),
        runtime_status: "UNPROVEN",
        runtime_proven: false,
    };
    let mut bytes = serde_json::to_vec_pretty(&stored)?;
    bytes.push(b'\n');
    write_atomic(&project.join("dist/last-published.json"), &bytes)?;
    write_atomic(&shadow_home.join("last-published.json"), &bytes)?;
    crate::runtime_artifacts::record_published(
        &project,
        shadow_home,
        &config.plugin_id,
        version_code,
        version_name,
        &artifact,
        &receipt.sha256,
    )?;
    sync_directory(&project.join("dist"))?;
    sync_directory(shadow_home)?;
    Ok(receipt)
}

fn create_private_directory(path: &Path) -> Result<()> {
    fs::create_dir_all(path).with_context(|| format!("create {}", path.display()))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
    }
    Ok(())
}

fn sync_directory(path: &Path) -> Result<()> {
    File::open(path)
        .with_context(|| format!("open directory {} for fsync", path.display()))?
        .sync_all()
        .with_context(|| format!("fsync directory {}", path.display()))
}

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .try_into()
        .unwrap_or(u64::MAX)
}

fn validate_version_args(args: &BuildArgs) -> Result<()> {
    if args.version_code == Some(0) {
        bail!("version code must be positive");
    }
    if let Some(version_name) = &args.version_name
        && !valid_version_name(version_name)
    {
        bail!("invalid version name: {version_name}");
    }
    Ok(())
}

pub(crate) fn run_gradle(
    context: &AppContext,
    project: &Path,
    environment: &BuildEnvironment,
    arguments: &[String],
) -> Result<GradleOutcome> {
    let wrapper = project.join("gradlew");
    if !wrapper.is_file() {
        bail!("Gradle wrapper not found: {}", wrapper.display());
    }
    let mut command = if let Some(distribution) = &environment.gradle_distribution {
        let mut command = Command::new(environment.java_home.join("bin/java"));
        command
            .arg("-Xms64m")
            .arg("-Xmx128m")
            .arg(format!(
                "-javaagent:{}",
                distribution
                    .join("lib/agents/gradle-instrumentation-agent-9.5.0.jar")
                    .display()
            ))
            .arg("-Dorg.gradle.appname=gradle")
            .arg("-jar")
            .arg(distribution.join("lib/gradle-gradle-cli-main-9.5.0.jar"));
        command
    } else {
        let shell = if Path::new("/system/bin/sh").is_file() {
            PathBuf::from("/system/bin/sh")
        } else {
            PathBuf::from("/bin/sh")
        };
        let mut command = Command::new(shell);
        command.arg(&wrapper);
        command
    };
    dependency::prepare_cache_layers(environment)?;
    if matches!(context.dependency_policy, DependencyPolicy::Offline)
        || (matches!(context.dependency_policy, DependencyPolicy::CacheFirst)
            && !context.allow_network)
    {
        command.arg("--offline");
    }
    if env::var("TERMUX_SHADOW_CONFIGURATION_CACHE").as_deref() == Ok("1")
        && !arguments.iter().any(|argument| argument == "--stop")
    {
        command.arg("--configuration-cache");
    }
    command
        .arg(format!(
            "-Pandroid.aapt2FromMavenOverride={}",
            environment.aapt2.display()
        ))
        .args(arguments)
        .current_dir(project);
    environment.apply(&mut command, context);
    let started = Instant::now();
    let output = command.output().context("start Gradle wrapper")?;
    let duration_ms = started.elapsed().as_millis().try_into().unwrap_or(u64::MAX);
    let stdout = String::from_utf8_lossy(&output.stdout);
    let stderr = String::from_utf8_lossy(&output.stderr);
    let combined = format!("{stdout}\n{stderr}");
    let log_path = gradle_log_path(project, arguments);
    let log = format!(
        "status={}\ndurationMs={}\narguments={}\n\n--- stdout ---\n{}\n\n--- stderr ---\n{}",
        output.status,
        duration_ms,
        arguments.join(" "),
        stdout,
        stderr
    );
    write_atomic(&log_path, log.as_bytes())?;
    if context.verbose {
        if context.json {
            eprint!("{stdout}{stderr}");
        } else {
            print!("{stdout}{stderr}");
        }
    }
    if !output.status.success() {
        return Err(GradleFailure::classify_with_policy(
            &combined,
            project,
            &log_path,
            context.dependency_policy,
            context.allow_network,
        )
        .into());
    }
    Ok(GradleOutcome {
        duration_ms,
        daemon: if combined.contains("Starting a Gradle Daemon") {
            "STARTED"
        } else {
            "REUSED"
        },
        warnings: parse_build_warnings(&combined),
    })
}

fn gradle_log_path(project: &Path, arguments: &[String]) -> PathBuf {
    let file_name = if arguments
        .iter()
        .any(|argument| argument == "publishShadowPluginDebug")
    {
        "last-publish.log"
    } else if arguments
        .iter()
        .any(|argument| argument == "copyShadowPluginDebugToDist")
    {
        "last-build.log"
    } else if arguments
        .iter()
        .any(|argument| argument == "validateShadowPluginDebug")
    {
        "last-doctor.log"
    } else if arguments.iter().any(|argument| argument == "--stop") {
        "last-stop.log"
    } else {
        "last-gradle.log"
    };
    project.join("build/logs").join(file_name)
}

pub fn print_gradle_summary(outcome: &GradleOutcome, indent: &str) {
    let daemon = match outcome.daemon {
        "STARTED" => "daemon started",
        "REUSED" => "daemon reused",
        value => value,
    };
    println!(
        "{indent}Gradle result: PASS in {:.2}s ({daemon})",
        outcome.duration_ms as f64 / 1000.0
    );
    println!(
        "{indent}build/tool warnings: {} (separate from plugin diagnostics)",
        outcome.warnings.len()
    );
    for warning in &outcome.warnings {
        println!("{indent}  [{}] {}", warning.code, warning.message);
    }
}

fn parse_build_warnings(output: &str) -> Vec<BuildWarning> {
    let mut warnings = Vec::new();
    for line in output.lines().map(str::trim) {
        if let Some(message) = line.strip_prefix("WARNING:") {
            let message = message.trim();
            let code = if message.contains("android.aapt2FromMavenOverride")
                && message.contains("experimental")
            {
                "AAPT2_OVERRIDE_EXPERIMENTAL"
            } else {
                "GRADLE_WARNING"
            };
            push_build_warning(&mut warnings, code, message);
        }
    }
    if output.contains("Deprecated Gradle features were used in this build") {
        push_build_warning(
            &mut warnings,
            "GRADLE_DEPRECATIONS",
            "build scripts use features that must be updated before Gradle 10",
        );
    }
    warnings
}

fn push_build_warning(warnings: &mut Vec<BuildWarning>, code: &str, message: &str) {
    if warnings.iter().any(|warning| warning.message == message) {
        return;
    }
    warnings.push(BuildWarning {
        code: code.to_owned(),
        message: message.to_owned(),
    });
}

fn invalidate_if_tooling_changed(project: &Path, quiet: bool) -> Result<()> {
    let fingerprint = tooling_fingerprint(project)?;
    let path = project.join(".gradle/shadow-tooling.fingerprint");
    let previous = fs::read_to_string(&path)
        .ok()
        .map(|value| value.trim().to_owned());
    if previous.as_deref() != Some(fingerprint.as_str()) {
        if !quiet {
            println!("Shadow tooling/config changed; invalidating stale build outputs.");
        }
        remove_dir_if_exists(&project.join("build"))?;
        remove_dir_if_exists(&project.join("plugin-app/build"))?;
    }
    Ok(())
}

fn persist_tooling_fingerprint(project: &Path) -> Result<()> {
    let fingerprint = tooling_fingerprint(project)?;
    write_atomic(
        &project.join(".gradle/shadow-tooling.fingerprint"),
        format!("{fingerprint}\n").as_bytes(),
    )
}

fn tooling_fingerprint(project: &Path) -> Result<String> {
    let paths = FINGERPRINT_INPUTS
        .iter()
        .map(|relative| project.join(relative))
        .collect::<Vec<_>>();
    sha256_paths(&paths)
}

pub(crate) fn write_local_properties(project: &Path, environment: &BuildEnvironment) -> Result<()> {
    write_atomic(
        &project.join("local.properties"),
        format!("sdk.dir={}\n", environment.android_home.display()).as_bytes(),
    )
}

fn dist_artifacts(project: &Path) -> Result<Vec<PathBuf>> {
    let dist = project.join("dist");
    if !dist.is_dir() {
        return Ok(Vec::new());
    }
    let mut artifacts = fs::read_dir(&dist)?
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| {
            path.file_name().and_then(|value| value.to_str())
                != Some(crate::runtime_artifacts::ACTIVE_ARTIFACT_NAME)
                && path.extension().and_then(|value| value.to_str()) == Some("shadowpkg")
        })
        .collect::<Vec<_>>();
    artifacts.sort();
    Ok(artifacts)
}

#[cfg(test)]
mod tests {
    use super::{
        dist_artifacts, enforce_publish_version, parse_build_warnings, publish_validated_to,
    };
    use crate::config::PluginConfig;
    use std::fs;

    #[test]
    fn separates_known_gradle_warnings() {
        let warnings = parse_build_warnings(
            "WARNING: The option setting 'android.aapt2FromMavenOverride=/tmp/aapt2' is experimental.\n\
             Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.\n",
        );
        assert_eq!(warnings.len(), 2);
        assert_eq!(warnings[0].code, "AAPT2_OVERRIDE_EXPERIMENTAL");
        assert_eq!(warnings[1].code, "GRADLE_DEPRECATIONS");
    }

    #[test]
    fn publish_labels_a_validated_cache_hit_without_gradle() {
        assert_eq!(super::cache_status(false, false, true), "HIT");
        assert_eq!(super::cache_status(true, false, true), "VALIDATED_REUSE");
        assert_eq!(super::cache_status(true, true, false), "FRESH");
        assert_eq!(super::cache_status(true, false, false), "MISS");
    }

    #[test]
    fn version_guard_distinguishes_downgrade_from_equal_version() {
        let downgrade = enforce_publish_version(9, Some(10), false).unwrap_err();
        assert!(format!("{downgrade:#}").starts_with("DOWNGRADE_BLOCKED:"));
        let equal = enforce_publish_version(10, Some(10), false).unwrap_err();
        assert!(format!("{equal:#}").starts_with("VERSION_NOT_INCREASING:"));
        assert!(enforce_publish_version(11, Some(10), false).is_ok());
        assert!(enforce_publish_version(9, Some(10), true).is_ok());
    }

    #[test]
    fn native_publisher_is_atomic_private_and_idempotent() {
        let temp = tempfile::tempdir().unwrap();
        let project = temp.path().join("project");
        let shadow_home = temp.path().join("shadow");
        let artifact = project.join("dist/plugin.shadowpkg");
        fs::create_dir_all(artifact.parent().unwrap()).unwrap();
        fs::write(&artifact, b"validated package").unwrap();
        let sha = crate::fsutil::sha256_file(&artifact).unwrap();
        let config = PluginConfig {
            schema_version: 1,
            plugin_slug: "test".into(),
            project_name: "test".into(),
            plugin_id: "com.termux.shadow.test".into(),
            part_key: "test".into(),
            namespace: "com.termux.shadow.test".into(),
            activity_class_name: "com.termux.shadow.test.MainActivity".into(),
            resource_package_id: "0x42".into(),
            plugin_apk_name: "plugin.apk".into(),
            bundle_base_name: "test".into(),
            display_name: "Test".into(),
            description: "Test".into(),
            default_version_code: 1,
            default_version_name: "1.0.0".into(),
            min_host_version_code: 1,
            max_host_version_code: 999,
            application_class_name: None,
            application_theme: "android.R.style.Theme_Material_Light_NoActionBar".into(),
            activity_theme: "android.R.style.Theme_Material_Light_NoActionBar".into(),
            screen_orientation: "unspecified".into(),
            soft_input_mode: "adjustNothing".into(),
            config_changes: "orientation|screenSize|keyboardHidden".into(),
        };
        let first =
            publish_validated_to(&project, &shadow_home, &config, 7, "1.2.3", &artifact, &sha)
                .unwrap();
        let second =
            publish_validated_to(&project, &shadow_home, &config, 7, "1.2.3", &artifact, &sha)
                .unwrap();
        assert_eq!(first.sha256, second.sha256);
        let target = shadow_home.join("inbox").join(&first.file_name);
        assert_eq!(crate::fsutil::sha256_file(&target).unwrap(), sha);
        assert!(project.join("dist/last-published.json").is_file());
        assert!(shadow_home.join("last-published.json").is_file());
        assert!(project.join("dist/last-runtime.json").is_file());
        assert!(project.join("dist/plugin.shadowpkg.runtime.json").is_file());
        let stored: serde_json::Value =
            serde_json::from_slice(&fs::read(project.join("dist/last-published.json")).unwrap())
                .unwrap();
        assert_eq!(stored["schemaVersion"], 2);
        assert_eq!(stored["runtimeStatus"], "UNPROVEN");
        assert_eq!(stored["runtimeProven"], false);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                target.metadata().unwrap().permissions().mode() & 0o777,
                0o600
            );
            assert_eq!(
                shadow_home
                    .join("inbox")
                    .metadata()
                    .unwrap()
                    .permissions()
                    .mode()
                    & 0o777,
                0o700
            );
        }
        fs::write(&target, b"tampered").unwrap();
        let error =
            publish_validated_to(&project, &shadow_home, &config, 7, "1.2.3", &artifact, &sha)
                .unwrap_err();
        assert!(format!("{error:#}").starts_with("PUBLISH_FAILED:"));
    }

    #[test]
    fn dist_discovery_never_treats_the_safe_active_pointer_as_a_build_output() {
        let temp = tempfile::tempdir().unwrap();
        let dist = temp.path().join("dist");
        fs::create_dir_all(&dist).unwrap();
        fs::write(dist.join("active.shadowpkg"), b"healthy").unwrap();
        fs::write(dist.join("notes-2.1.4.shadowpkg"), b"candidate").unwrap();
        let artifacts = dist_artifacts(temp.path()).unwrap();
        assert_eq!(artifacts, vec![dist.join("notes-2.1.4.shadowpkg")]);
    }
}
