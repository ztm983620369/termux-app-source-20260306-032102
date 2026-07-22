use std::time::Instant;

use anyhow::Result;
use regex::Regex;
use serde::Serialize;

use crate::build::{BuildOutput, execute_build, highest_committed_version_code};
use crate::cache;
use crate::cli::{BuildArgs, BumpKind, DeployArgs, DevArgs, LaunchArgs};
use crate::config::{PluginConfig, valid_version_name};
use crate::context::{AppContext, BuildEnvironment};
use crate::control::{LaunchOutcome, execute_launch};
use crate::doctor;
use crate::errors;
use crate::history::{self, OperationRecord};
use crate::status::{PluginRecord, RegistryReport, VersionRecord, read_registry};

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct DevOutput {
    ok: bool,
    action: &'static str,
    status: &'static str,
    #[serde(rename = "workerOperationId")]
    operation_id: String,
    project: String,
    project_resolution: &'static str,
    plugin_id: String,
    source_fingerprint: String,
    toolchain_fingerprint: String,
    dirty_since_active: bool,
    next_version_code: u64,
    version: Option<DevVersion>,
    stages: DevStages,
    timings: DevTimings,
    diagnostic_summary: DevDiagnosticSummary,
    runtime_health: &'static str,
    launch: Option<LaunchOutcome>,
    duration_ms: u64,
    state_changed: bool,
    history_path: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct DevVersion {
    version_code: u64,
    version_name: String,
    generation: Option<String>,
    sha256: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct DevStages {
    doctor: &'static str,
    build: DevBuildStage,
    publish: &'static str,
    run: &'static str,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct DevTimings {
    doctor_ms: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    build_ms: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    publish_ms: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    run_ms: Option<u64>,
}

#[derive(Debug, Serialize)]
struct DevDiagnosticSummary {
    errors: usize,
    warnings: usize,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct DevBuildStage {
    status: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    daemon: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    duration_ms: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    gradle_duration_ms: Option<u64>,
    #[serde(skip_serializing_if = "is_zero")]
    warning_count: usize,
}

impl DevBuildStage {
    fn skipped() -> Self {
        Self {
            status: "SKIPPED",
            daemon: None,
            duration_ms: None,
            gradle_duration_ms: None,
            warning_count: 0,
        }
    }

    fn from_build(build: &BuildOutput) -> Self {
        Self {
            status: match build.cache {
                "HIT" | "VALIDATED_REUSE" => "REUSED",
                "MISS" | "FRESH" => "BUILT",
                value => value,
            },
            daemon: build.gradle.as_ref().map(|gradle| gradle.daemon),
            duration_ms: Some(build.timings.build_ms),
            gradle_duration_ms: build.gradle.as_ref().map(|gradle| gradle.duration_ms),
            warning_count: build
                .gradle
                .as_ref()
                .map(|gradle| gradle.warnings.len())
                .unwrap_or_default(),
        }
    }
}

fn is_zero(value: &usize) -> bool {
    *value == 0
}

struct MatchingVersion {
    version: VersionRecord,
    role: &'static str,
    input_fingerprint: String,
}

pub fn run(context: &AppContext, args: DevArgs) -> Result<()> {
    let mut args = args;
    args.run = !args.no_run;
    run_named(context, args, "dev")
}

pub fn run_deploy(context: &AppContext, args: DeployArgs) -> Result<()> {
    run_named(
        context,
        DevArgs {
            run: args.run,
            no_run: false,
            version_name: args.version_name,
            bump: args.bump,
            fresh: args.fresh,
            timeout: args.timeout,
        },
        "deploy",
    )
}

fn run_named(context: &AppContext, args: DevArgs, action: &'static str) -> Result<()> {
    let started = Instant::now();
    let resolved_project = context.resolve_project()?;
    let project = resolved_project.path;
    let config = match PluginConfig::load(&project.join("shadow-plugin.properties")) {
        Ok(config) => config,
        Err(config_error) => {
            let error = match doctor::validate_for_project(context, &project, false, true) {
                Ok(()) => config_error,
                Err(error) => error,
            };
            return Err(errors::with_development_context(
                error,
                None,
                project.display().to_string(),
                None,
                None,
            ));
        }
    };
    let registry = if context.shadow_home.join("reports/registry.json").is_file() {
        read_registry(&context.shadow_home)
            .map_err(|error| development_error(context, &config, &project, None, error))?
    } else {
        RegistryReport::default()
    };
    let plugin = registry
        .plugins
        .iter()
        .find(|plugin| plugin.plugin_id == config.plugin_id);
    let historical_version = history::highest_version(&context.shadow_home, &config.plugin_id)
        .map_err(|error| development_error(context, &config, &project, None, error))?;
    let highest = [
        highest_version(plugin),
        highest_committed_version_code(context, &config)
            .map_err(|error| development_error(context, &config, &project, None, error))?,
    ]
    .into_iter()
    .flatten()
    .max();
    let next_version_code = highest
        .map(|version| version.saturating_add(1))
        .unwrap_or(config.default_version_code);
    let doctor_started = Instant::now();
    doctor::validate_for_project(context, &project, false, true).map_err(|error| {
        development_error(context, &config, &project, Some(next_version_code), error)
    })?;
    let doctor_ms = elapsed_ms(doctor_started);
    crate::runtime_artifacts::reconcile_project(context, &config.plugin_id).map_err(|error| {
        development_error(context, &config, &project, Some(next_version_code), error)
    })?;
    let environment = context.build_environment().map_err(|error| {
        development_error(context, &config, &project, Some(next_version_code), error)
    })?;
    let source_fingerprint =
        cache::source_fingerprint(&project, &environment).map_err(|error| {
            development_error(context, &config, &project, Some(next_version_code), error)
        })?;
    let toolchain_fingerprint = cache::toolchain_fingerprint(&environment).map_err(|error| {
        development_error(context, &config, &project, Some(next_version_code), error)
    })?;
    let operation_id = history::operation_id(action);
    let history_match =
        history::latest_for_source(&context.shadow_home, &config.plugin_id, &source_fingerprint)
            .map_err(|error| {
                development_error(context, &config, &project, Some(next_version_code), error)
            })?;
    let matching = if should_resume_registered(args.fresh) {
        matching_registered_version(&project, &environment, plugin, history_match.as_ref())
            .map_err(|error| {
                development_error(context, &config, &project, Some(next_version_code), error)
            })?
    } else {
        None
    };

    if let Some(matching) = matching {
        return resume_registered(
            context,
            args,
            &project,
            &config,
            source_fingerprint,
            toolchain_fingerprint,
            operation_id,
            next_version_code,
            matching,
            doctor_ms,
            started,
            action,
            resolved_project.source,
        );
    }

    let version_name = match args.version_name {
        Some(version_name) if valid_version_name(&version_name) => version_name,
        Some(version_name) => {
            return Err(development_error(
                context,
                &config,
                &project,
                Some(next_version_code),
                anyhow::anyhow!(
                    "VERSION_NAME_INVALID: version name {version_name:?} is invalid; use digits, letters, '.', '-' or '+' without whitespace"
                ),
            ));
        }
        None => next_version_name(
            plugin,
            historical_version.as_ref(),
            &config.default_version_name,
            args.bump,
        ),
    };
    let input_fingerprint =
        cache::input_fingerprint(&project, &environment, next_version_code, &version_name)
            .map_err(|error| {
                development_error(context, &config, &project, Some(next_version_code), error)
            })?;
    let state_before = plugin
        .and_then(|plugin| plugin.active_generation.clone())
        .unwrap_or_else(|| "UNREGISTERED".to_owned());
    let build_result = execute_build(
        context,
        BuildArgs {
            version_code: Some(next_version_code),
            version_name: Some(version_name.clone()),
            fresh: args.fresh,
        },
        true,
        true,
        args.timeout,
        false,
    );
    let build = match build_result {
        Ok(build) => build,
        Err(error) => {
            let error_code = errors::code(&error);
            let mut record = base_record(
                &operation_id,
                "FAILED",
                &config,
                &project,
                source_fingerprint,
                toolchain_fingerprint,
                started,
                action,
            );
            record.input_fingerprint = Some(input_fingerprint);
            record.state_before = Some(state_before);
            record.error_code = Some(error_code);
            let _ = history::append(&context.shadow_home, &record);
            return Err(development_error(
                context,
                &config,
                &project,
                Some(next_version_code),
                error,
            ));
        }
    };
    complete_published(
        context,
        args.run,
        args.timeout,
        &project,
        &config,
        source_fingerprint,
        toolchain_fingerprint,
        operation_id,
        next_version_code,
        input_fingerprint,
        state_before,
        build,
        doctor_ms,
        started,
        action,
        resolved_project.source,
    )
}

#[allow(clippy::too_many_arguments)]
fn resume_registered(
    context: &AppContext,
    args: DevArgs,
    project: &std::path::Path,
    config: &PluginConfig,
    source_fingerprint: String,
    toolchain_fingerprint: String,
    operation_id: String,
    next_version_code: u64,
    matching: MatchingVersion,
    doctor_ms: u64,
    started: Instant,
    action: &'static str,
    project_resolution: &'static str,
) -> Result<()> {
    let manifest = matching.version.manifest.as_ref();
    let version_code = manifest
        .and_then(|manifest| manifest.version_code)
        .unwrap_or(0);
    let version_name = manifest
        .and_then(|manifest| manifest.version_name.clone())
        .unwrap_or_else(|| "unknown".to_owned());
    let runtime_verified = matching.version.runtime_health_protocol_version >= 1
        && matching.version.runtime_stable_at > 0
        && matching.version.last_healthy_process_pid > 0;
    let needs_run = args.run
        && (matching.role == "CANDIDATE" || (matching.role == "ACTIVE" && !runtime_verified));
    let run_started = needs_run.then(Instant::now);
    let launch = if needs_run {
        match execute_launch(
            context,
            LaunchArgs {
                plugin_id: Some(config.plugin_id.clone()),
                no_wait: false,
                force: false,
                timeout: args.timeout,
            },
            false,
            false,
        ) {
            Ok(launch) => Some(launch),
            Err(error) => {
                let mut record = base_record(
                    &operation_id,
                    "ACTIVATION_FAILED",
                    config,
                    project,
                    source_fingerprint,
                    toolchain_fingerprint,
                    started,
                    action,
                );
                record.input_fingerprint = Some(matching.input_fingerprint);
                record.version_code = Some(version_code);
                record.version_name = Some(version_name);
                record.artifact_sha256 = Some(matching.version.bundle_sha256);
                record.state_before = Some(matching.role.to_owned());
                record.state_after = Some("ACTIVATION_FAILED".to_owned());
                record.error_code = Some(errors::code(&error));
                let _ = history::append(&context.shadow_home, &record);
                return Err(development_error(
                    context,
                    config,
                    project,
                    Some(next_version_code),
                    error,
                ));
            }
        }
    } else {
        None
    };
    let run_ms = run_started.map(elapsed_ms);

    let status = if launch.is_some() {
        "ACTIVE"
    } else if matching.role == "ACTIVE" && runtime_verified {
        "NO_CHANGES"
    } else if matching.role == "ACTIVE" {
        "RUNTIME_VALIDATION_REQUIRED"
    } else {
        "CANDIDATE_REGISTERED"
    };
    let mut record = base_record(
        &operation_id,
        status,
        config,
        project,
        source_fingerprint.clone(),
        toolchain_fingerprint.clone(),
        started,
        action,
    );
    record.input_fingerprint = Some(matching.input_fingerprint);
    record.version_code = Some(version_code);
    record.version_name = Some(version_name.clone());
    record.artifact_sha256 = Some(matching.version.bundle_sha256.clone());
    record.state_before = Some(matching.role.to_owned());
    record.state_after = Some(status.to_owned());
    let history_path = history::append(&context.shadow_home, &record)?;
    emit(
        context,
        DevOutput {
            ok: true,
            action,
            status,
            operation_id,
            project: project.display().to_string(),
            project_resolution,
            plugin_id: config.plugin_id.clone(),
            source_fingerprint,
            toolchain_fingerprint,
            dirty_since_active: dirty_after_resume(matching.role, launch.is_some()),
            next_version_code,
            version: Some(DevVersion {
                version_code,
                version_name,
                generation: Some(matching.version.generation),
                sha256: Some(matching.version.bundle_sha256),
            }),
            stages: DevStages {
                doctor: "PASS",
                build: DevBuildStage::skipped(),
                publish: "ALREADY_REGISTERED",
                run: if launch.is_some() {
                    "HEALTHY"
                } else {
                    "SKIPPED"
                },
            },
            timings: DevTimings {
                doctor_ms,
                build_ms: None,
                publish_ms: None,
                run_ms,
            },
            diagnostic_summary: DevDiagnosticSummary {
                errors: 0,
                warnings: 0,
            },
            runtime_health: if launch.is_some() || (matching.role == "ACTIVE" && runtime_verified) {
                "HEALTHY"
            } else {
                "UNPROVEN"
            },
            launch,
            duration_ms: elapsed_ms(started),
            state_changed: needs_run,
            history_path: history_path.display().to_string(),
        },
    )
}

#[allow(clippy::too_many_arguments)]
fn complete_published(
    context: &AppContext,
    run: bool,
    timeout: u64,
    project: &std::path::Path,
    config: &PluginConfig,
    source_fingerprint: String,
    toolchain_fingerprint: String,
    operation_id: String,
    next_version_code: u64,
    input_fingerprint: String,
    state_before: String,
    build: BuildOutput,
    doctor_ms: u64,
    started: Instant,
    action: &'static str,
    project_resolution: &'static str,
) -> Result<()> {
    let build_stage = DevBuildStage::from_build(&build);
    let build_warning_count = build_stage.warning_count;
    let build_ms = build.timings.build_ms;
    let publish_ms = build.timings.publish_ms;
    let artifact = build
        .artifacts
        .first()
        .ok_or_else(|| anyhow::anyhow!("dev publish returned no artifact"))?;
    let artifact_sha = artifact.sha256.clone();
    let run_started = run.then(Instant::now);
    let launch = if run {
        match execute_launch(
            context,
            LaunchArgs {
                plugin_id: Some(config.plugin_id.clone()),
                no_wait: false,
                force: false,
                timeout,
            },
            false,
            false,
        ) {
            Ok(launch) => Some(launch),
            Err(error) => {
                let mut record = base_record(
                    &operation_id,
                    "ACTIVATION_FAILED",
                    config,
                    project,
                    source_fingerprint,
                    toolchain_fingerprint,
                    started,
                    action,
                );
                record.input_fingerprint = Some(input_fingerprint);
                record.version_code = Some(build.version_code);
                record.version_name = Some(build.version_name);
                record.artifact_sha256 = Some(artifact_sha);
                record.state_before = Some(state_before);
                record.state_after = Some("ACTIVATION_FAILED".to_owned());
                record.error_code = Some(errors::code(&error));
                let _ = history::append(&context.shadow_home, &record);
                return Err(development_error(
                    context,
                    config,
                    project,
                    Some(next_version_code.saturating_add(1)),
                    error,
                ));
            }
        }
    } else {
        None
    };
    let run_ms = run_started.map(elapsed_ms);
    let status = if launch.is_some() {
        "ACTIVE"
    } else {
        "CANDIDATE_REGISTERED"
    };
    let generation = launch
        .as_ref()
        .and_then(|launch| launch.generation.clone())
        .or_else(|| {
            registered_generation(context, &config.plugin_id, &artifact_sha)
                .ok()
                .flatten()
        });
    let mut record = base_record(
        &operation_id,
        status,
        config,
        project,
        source_fingerprint.clone(),
        toolchain_fingerprint.clone(),
        started,
        action,
    );
    record.input_fingerprint = Some(input_fingerprint);
    record.version_code = Some(build.version_code);
    record.version_name = Some(build.version_name.clone());
    record.artifact_sha256 = Some(artifact_sha.clone());
    record.state_before = Some(state_before);
    record.state_after = Some(status.to_owned());
    let history_path = history::append(&context.shadow_home, &record)?;
    emit(
        context,
        DevOutput {
            ok: true,
            action,
            status,
            operation_id,
            project: project.display().to_string(),
            project_resolution,
            plugin_id: config.plugin_id.clone(),
            source_fingerprint,
            toolchain_fingerprint,
            dirty_since_active: !run,
            next_version_code: next_version_code.saturating_add(1),
            version: Some(DevVersion {
                version_code: build.version_code,
                version_name: build.version_name,
                generation,
                sha256: Some(artifact_sha),
            }),
            stages: DevStages {
                doctor: "PASS",
                build: build_stage,
                publish: "REGISTERED",
                run: if launch.is_some() {
                    "HEALTHY"
                } else {
                    "SKIPPED"
                },
            },
            timings: DevTimings {
                doctor_ms,
                build_ms: Some(build_ms),
                publish_ms,
                run_ms,
            },
            diagnostic_summary: DevDiagnosticSummary {
                errors: 0,
                warnings: build_warning_count,
            },
            runtime_health: if launch.is_some() {
                "HEALTHY"
            } else {
                "UNPROVEN"
            },
            launch,
            duration_ms: elapsed_ms(started),
            state_changed: true,
            history_path: history_path.display().to_string(),
        },
    )
}

fn matching_registered_version(
    project: &std::path::Path,
    environment: &BuildEnvironment,
    plugin: Option<&PluginRecord>,
    history_match: Option<&OperationRecord>,
) -> Result<Option<MatchingVersion>> {
    let Some(plugin) = plugin else {
        return Ok(None);
    };
    for (role, generation) in [
        ("ACTIVE", plugin.active_generation.as_deref()),
        ("ACTIVATING", plugin.activating_generation.as_deref()),
        ("CANDIDATE", plugin.candidate_generation.as_deref()),
    ] {
        let Some(generation) = generation else {
            continue;
        };
        let Some(version) = plugin
            .versions
            .iter()
            .find(|version| version.generation == generation)
        else {
            continue;
        };
        let Some(manifest) = &version.manifest else {
            continue;
        };
        let (Some(version_code), Some(version_name)) =
            (manifest.version_code, manifest.version_name.as_deref())
        else {
            continue;
        };
        let input_fingerprint =
            cache::input_fingerprint(project, environment, version_code, version_name)?;
        let history_matches = history_match.is_some_and(|record| {
            record.artifact_sha256.as_deref() == Some(version.bundle_sha256.as_str())
        });
        let cache_matches = if history_matches {
            true
        } else {
            cache::lookup(
                project,
                &input_fingerprint,
                version_code,
                version_name,
                true,
            )?
            .is_some_and(|hit| hit.sha256 == version.bundle_sha256)
        };
        if cache_matches {
            return Ok(Some(MatchingVersion {
                version: version.clone(),
                role,
                input_fingerprint,
            }));
        }
    }
    Ok(None)
}

fn registered_generation(
    context: &AppContext,
    plugin_id: &str,
    sha256: &str,
) -> Result<Option<String>> {
    let registry = read_registry(&context.shadow_home)?;
    Ok(registry
        .plugins
        .iter()
        .find(|plugin| plugin.plugin_id == plugin_id)
        .and_then(|plugin| {
            plugin
                .versions
                .iter()
                .find(|version| version.bundle_sha256 == sha256)
        })
        .map(|version| version.generation.clone()))
}

fn highest_version(plugin: Option<&PluginRecord>) -> Option<u64> {
    plugin.and_then(|plugin| {
        plugin
            .versions
            .iter()
            .filter_map(|version| version.manifest.as_ref()?.version_code)
            .max()
    })
}

fn development_error(
    context: &AppContext,
    config: &PluginConfig,
    project: &std::path::Path,
    next_version_code: Option<u64>,
    error: anyhow::Error,
) -> anyhow::Error {
    errors::with_development_context(
        error,
        Some(config.plugin_id.clone()),
        project.display().to_string(),
        current_healthy(context, &config.plugin_id),
        next_version_code,
    )
}

fn current_healthy(context: &AppContext, plugin_id: &str) -> Option<errors::DevelopmentVersion> {
    let registry = read_registry(&context.shadow_home).ok()?;
    let plugin = registry
        .plugins
        .iter()
        .find(|plugin| plugin.plugin_id == plugin_id)?;
    let generation = plugin.active_generation.as_deref()?;
    let version = plugin
        .versions
        .iter()
        .find(|version| version.generation == generation)?;
    let runtime_verified = version.runtime_health_protocol_version >= 1
        && version.runtime_stable_at > 0
        && version.last_healthy_process_pid > 0;
    if !runtime_verified {
        return None;
    }
    Some(errors::DevelopmentVersion {
        version_code: version
            .manifest
            .as_ref()
            .and_then(|manifest| manifest.version_code),
        version_name: version
            .manifest
            .as_ref()
            .and_then(|manifest| manifest.version_name.clone()),
        generation: version.generation.clone(),
        sha256: version.bundle_sha256.clone(),
    })
}

fn should_resume_registered(fresh: bool) -> bool {
    !fresh
}

fn dirty_after_resume(role: &str, activated: bool) -> bool {
    !activated && role != "ACTIVE"
}

fn next_version_name(
    plugin: Option<&PluginRecord>,
    historical: Option<&OperationRecord>,
    default: &str,
    bump: BumpKind,
) -> String {
    let registry_version = plugin.and_then(|plugin| {
        plugin
            .versions
            .iter()
            .filter_map(|version| {
                let manifest = version.manifest.as_ref()?;
                Some((manifest.version_code?, manifest.version_name.as_deref()?))
            })
            .max_by_key(|(version_code, _)| *version_code)
            .map(|(version_code, version_name)| (version_code, version_name.to_owned()))
    });
    let historical_version =
        historical.and_then(|record| Some((record.version_code?, record.version_name.clone()?)));
    [registry_version, historical_version]
        .into_iter()
        .flatten()
        .max_by_key(|(version_code, _)| *version_code)
        .and_then(|(_, version)| bump_version(&version, bump))
        .unwrap_or_else(|| default.to_owned())
}

fn bump_version(version: &str, bump: BumpKind) -> Option<String> {
    let pattern = Regex::new(r"^(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$").ok()?;
    let captures = pattern.captures(version)?;
    let major = captures.get(1)?.as_str().parse::<u64>().ok()?;
    let minor = captures.get(2)?.as_str().parse::<u64>().ok()?;
    let patch = captures.get(3)?.as_str().parse::<u64>().ok()?;
    Some(match bump {
        BumpKind::Patch => format!("{major}.{minor}.{}", patch.saturating_add(1)),
        BumpKind::Minor => format!("{major}.{}.0", minor.saturating_add(1)),
        BumpKind::Major => format!("{}.0.0", major.saturating_add(1)),
    })
}

#[allow(clippy::too_many_arguments)]
fn base_record(
    operation_id: &str,
    status: &str,
    config: &PluginConfig,
    project: &std::path::Path,
    source_fingerprint: String,
    toolchain_fingerprint: String,
    started: Instant,
    action: &'static str,
) -> OperationRecord {
    let mut record = OperationRecord::new(
        operation_id.to_owned(),
        action,
        status,
        &config.plugin_id,
        project,
        source_fingerprint,
        toolchain_fingerprint,
    );
    record.duration_ms = elapsed_ms(started);
    record
}

fn emit(context: &AppContext, output: DevOutput) -> Result<()> {
    if context.json {
        println!("{}", serde_json::to_string_pretty(&output)?);
    } else {
        println!("dev: {}  {}", output.status, output.plugin_id);
        println!(
            "  project={} ({})",
            output.project, output.project_resolution
        );
        if let Some(version) = &output.version {
            println!(
                "  healthy={} (code {}) generation={}",
                version.version_name,
                version.version_code,
                version.generation.as_deref().unwrap_or("pending")
            );
            if context.verbose {
                println!(
                    "  sha256={}",
                    version.sha256.as_deref().unwrap_or("pending")
                );
            }
        }
        println!(
            "  stages: doctor={} build={} publish={} run={}",
            output.stages.doctor,
            output.stages.build.status,
            output.stages.publish,
            output.stages.run
        );
        println!(
            "  health={} nextVersionCode={}",
            output.runtime_health, output.next_version_code
        );
        println!("  next: edit source, then run `shadow-plugin dev` again");
        if context.verbose {
            println!("  history={}", output.history_path);
        }
    }
    Ok(())
}

fn elapsed_ms(started: Instant) -> u64 {
    started.elapsed().as_millis().try_into().unwrap_or(u64::MAX)
}

#[cfg(test)]
mod tests {
    use super::{bump_version, dirty_after_resume, should_resume_registered};
    use crate::cli::BumpKind;

    #[test]
    fn increments_semantic_patch_versions() {
        assert_eq!(
            bump_version("2.1.0", BumpKind::Patch).as_deref(),
            Some("2.1.1")
        );
        assert_eq!(
            bump_version("2.1.0-beta+7", BumpKind::Minor).as_deref(),
            Some("2.2.0")
        );
        assert_eq!(
            bump_version("2.1.0", BumpKind::Major).as_deref(),
            Some("3.0.0")
        );
        assert_eq!(bump_version("release", BumpKind::Patch), None);
    }

    #[test]
    fn fresh_deploy_never_uses_the_registered_no_changes_fast_path() {
        assert!(should_resume_registered(false));
        assert!(!should_resume_registered(true));
    }

    #[test]
    fn resumed_candidate_is_clean_after_successful_activation() {
        assert!(dirty_after_resume("CANDIDATE", false));
        assert!(!dirty_after_resume("CANDIDATE", true));
        assert!(!dirty_after_resume("ACTIVE", false));
    }
}
