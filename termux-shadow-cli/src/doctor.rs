use std::env;
use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};

use anyhow::{Result, bail};
use regex::Regex;
use serde::Serialize;
use walkdir::WalkDir;

use crate::build;
use crate::cli::DoctorArgs;
use crate::config::{PluginConfig, parse_properties, sibling_configs};
use crate::context::{AppContext, PROJECT_CONFIG, same_logical_path};
use crate::control;
use crate::errors;
use crate::status::read_registry;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Diagnostic {
    pub level: Level,
    pub code: &'static str,
    pub message: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum Level {
    Ok,
    Warn,
    Fail,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct DoctorOutput {
    ok: bool,
    action: &'static str,
    project: String,
    plugin_id: Option<String>,
    resource_package_id: Option<String>,
    plugin_errors: usize,
    plugin_warnings: usize,
    checks: Vec<Diagnostic>,
    package_validation: Option<PackageValidationReport>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct PackageValidationReport {
    status: &'static str,
    cache: Option<&'static str>,
    artifact: Option<String>,
    sha256: Option<String>,
    gradle: Option<build::GradleOutcome>,
    error: Option<String>,
}

pub fn run(context: &AppContext, args: DoctorArgs) -> Result<()> {
    let project = context.project()?;
    run_for_project(context, &project, args)
}

pub fn run_for_project(context: &AppContext, project: &Path, args: DoctorArgs) -> Result<()> {
    if args.full && args.project_only {
        bail!("--full cannot be combined with --project-only");
    }
    let (config, checks) = fast_checks(context, project, args.project_only, args.publish);
    let plugin_errors = count(&checks, Level::Fail);
    let plugin_warnings = count(&checks, Level::Warn);
    let report_checks = if args.failures_only {
        checks
            .iter()
            .filter(|check| check.level != Level::Ok)
            .cloned()
            .collect::<Vec<_>>()
    } else {
        checks.clone()
    };

    if !context.json {
        print_human_checks(project, config.as_ref(), &report_checks);
    }
    if plugin_errors > 0 {
        if !context.json {
            emit_summary_or_json(
                context,
                project,
                config.as_ref(),
                report_checks,
                plugin_errors,
                plugin_warnings,
                None,
            )?;
        }
        return Err(failed_checks_error(&checks));
    }

    let package_validation = if args.full {
        if !context.json {
            println!("\nPackage validation");
            println!("  native cache: checking content fingerprint...");
            io::stdout().flush().ok();
        }
        let config = config
            .as_ref()
            .expect("successful fast checks always provide config");
        let outcome = build::validate_package(context, project, config, args.fresh)?;
        Some(PackageValidationReport {
            status: "PASS",
            cache: Some(outcome.cache),
            artifact: Some(outcome.artifact),
            sha256: Some(outcome.sha256),
            gradle: outcome.gradle,
            error: None,
        })
    } else {
        None
    };
    emit_summary_or_json(
        context,
        project,
        config.as_ref(),
        report_checks,
        plugin_errors,
        plugin_warnings,
        package_validation,
    )?;
    Ok(())
}

pub fn validate_for_project(
    context: &AppContext,
    project: &Path,
    project_only: bool,
    publish: bool,
) -> Result<()> {
    let (_, checks) = fast_checks(context, project, project_only, publish);
    let failures = checks
        .iter()
        .filter(|check| check.level == Level::Fail)
        .collect::<Vec<_>>();
    if failures.is_empty() {
        Ok(())
    } else {
        Err(failed_checks_error(&checks))
    }
}

fn failed_checks_error(checks: &[Diagnostic]) -> anyhow::Error {
    let failures = checks
        .iter()
        .filter(|check| check.level == Level::Fail)
        .collect::<Vec<_>>();
    let first = failures[0];
    let remaining = failures
        .iter()
        .skip(1)
        .map(|check| format!("{}: {}", check.code, check.message))
        .collect::<Vec<_>>();
    let message = if remaining.is_empty() {
        first.message.clone()
    } else {
        format!(
            "{}; additional failures: {}",
            first.message,
            remaining.join("; ")
        )
    };
    let file = match first.code {
        "ANDROID_LIBRARY_PLUGIN_UNDECLARED" => first
            .message
            .split_once(" applies ")
            .map(|(path, _)| path.to_owned()),
        "ANDROID_LIBRARY_PLUGIN_MAPPING_MISSING" => Some("settings.gradle".to_owned()),
        "CONFIG_MISSING" => first.message.strip_prefix("missing ").map(str::to_owned),
        _ => None,
    };
    errors::preflight_failure(first.code, message, file)
}

pub fn fast_checks(
    context: &AppContext,
    project: &Path,
    project_only: bool,
    publish: bool,
) -> (Option<PluginConfig>, Vec<Diagnostic>) {
    let mut checks = Vec::new();
    let sync_marker = project.join(crate::sync::SYNC_MARKER);
    if sync_marker.is_file() {
        checks.push(fail(
            "TOOLING_SYNC_INCOMPLETE",
            format!(
                "an interrupted tooling sync marker exists at {}; rerun `shadow-plugin sync` before building or publishing",
                sync_marker.display()
            ),
        ));
    }
    let config_path = project.join(PROJECT_CONFIG);
    if !config_path.is_file() {
        checks.push(fail(
            "CONFIG_MISSING",
            format!("missing {}", config_path.display()),
        ));
        return (None, checks);
    }

    match parse_properties(&config_path) {
        Ok(properties) if properties.duplicates.is_empty() => {
            checks.push(ok("CONFIG_KEYS_UNIQUE", "property keys are unique"))
        }
        Ok(properties) => checks.push(fail(
            "CONFIG_DUPLICATE_KEY",
            format!(
                "duplicate properties: {}",
                properties
                    .duplicates
                    .into_iter()
                    .collect::<Vec<_>>()
                    .join(", ")
            ),
        )),
        Err(error) => checks.push(fail("CONFIG_PARSE", format!("{error:#}"))),
    }

    let config = match PluginConfig::load(&config_path) {
        Ok(config) => config,
        Err(error) => {
            checks.push(fail("CONFIG_INVALID", format!("{error:#}")));
            return (None, checks);
        }
    };
    let validation = config.validate();
    if validation.is_empty() {
        checks.push(ok(
            "IDENTITY_VALID",
            format!(
                "identity is valid: {} / {} / {}",
                config.plugin_id, config.part_key, config.resource_package_id
            ),
        ));
    } else {
        checks.extend(
            validation
                .into_iter()
                .map(|message| fail("IDENTITY_INVALID", message)),
        );
    }

    let activity_path = config.activity_class_name.replace('.', "/");
    let java = project.join(format!("plugin-app/src/main/java/{activity_path}.java"));
    let kotlin = project.join(format!("plugin-app/src/main/kotlin/{activity_path}.kt"));
    if java.is_file() || kotlin.is_file() {
        checks.push(ok("ACTIVITY_SOURCE", "configured Activity source exists"));
    } else {
        checks.push(fail(
            "ACTIVITY_SOURCE_MISSING",
            format!(
                "Activity source {} was not found; create {} or {}, or correct activityClassName in shadow-plugin.properties",
                config.activity_class_name,
                java.display(),
                kotlin.display()
            ),
        ));
    }

    let manifest = project.join("plugin-app/src/main/AndroidManifest.xml");
    match fs::read_to_string(&manifest) {
        Ok(text)
            if text.contains("${shadowActivityClassName}")
                && text.contains("${shadowDisplayName}") =>
        {
            checks.push(ok(
                "MANIFEST_PROPERTY_DRIVEN",
                "Android manifest identity uses config placeholders",
            ));
        }
        Ok(_) => checks.push(fail(
            "MANIFEST_IDENTITY_LITERAL",
            "Android manifest is not fully property-driven",
        )),
        Err(error) => checks.push(fail(
            "MANIFEST_MISSING",
            format!("read {}: {error}", manifest.display()),
        )),
    }

    let android_library_marker_available = android_library_marker_available(context, project);
    if let Some(check) = android_library_plugin_check(project, android_library_marker_available) {
        checks.push(check);
    }
    checks.extend(compile_only_boundary_checks(project));

    for (relative, code, label) in [
        (
            "shadow/loader/sample-loader-debug.apk",
            "LOADER_APK",
            "Shadow loader APK",
        ),
        (
            "shadow/runtime/sample-runtime-debug.apk",
            "RUNTIME_APK",
            "Shadow runtime APK",
        ),
        (
            "shadow/compile-only/shadow-runtime.jar",
            "SHADOW_API",
            "compile-only Shadow runtime API",
        ),
        ("gradlew", "GRADLE_WRAPPER", "Gradle wrapper"),
    ] {
        let path = project.join(relative);
        if path.is_file() {
            checks.push(ok(code, format!("{label}: {}", path.display())));
        } else {
            checks.push(fail(code, format!("missing {label}: {}", path.display())));
        }
    }

    if legacy_zip(project) {
        checks.push(fail(
            "LEGACY_PACKAGE",
            "legacy plugin-debug.zip exists in project",
        ));
    } else {
        checks.push(ok(
            "SINGLE_INGRESS",
            "no legacy fixed-path plugin ZIP exists",
        ));
    }

    if !project_only {
        match context.build_environment_for_project(project) {
            Ok(environment) => {
                checks.push(ok(
                    "JAVA",
                    format!("Java: {}", environment.java_home.join("bin/java").display()),
                ));
                checks.push(ok(
                    "ANDROID_SDK",
                    format!("Android SDK: {}", environment.android_home.display()),
                ));
                checks.push(ok(
                    "AAPT2",
                    format!("aapt2: {}", environment.aapt2.display()),
                ));
                if let Some(root) = environment.portable_root {
                    checks.push(ok(
                        "PORTABLE_TOOLCHAIN",
                        format!("portable base toolchain: {}", root.display()),
                    ));
                } else {
                    checks.push(warn(
                        "HOST_TOOLCHAIN",
                        "using host environment rather than the portable Termux toolchain",
                    ));
                }
            }
            Err(error) => checks.push(fail("TOOLCHAIN", format!("{error:#}"))),
        }

        if Path::new("/system/bin/am").is_file() {
            match control::ping(context) {
                Ok(message) => checks.push(ok("HOST_CONTROL", message)),
                Err(error) if publish => checks.push(fail(
                    "HOST_CONTROL",
                    format!("Host control is required for a closed-loop publish: {error:#}"),
                )),
                Err(error) => checks.push(warn(
                    "HOST_CONTROL",
                    format!("Host control is unavailable: {error:#}"),
                )),
            }
        }

        let registry_path = context.shadow_home.join("reports/registry.json");
        if registry_path.is_file() {
            match read_registry(&context.shadow_home) {
                Ok(registry) => {
                    let owner = registry.plugins.iter().find(|plugin| {
                        plugin.versions.iter().any(|version| {
                            version
                                .manifest
                                .as_ref()
                                .and_then(|manifest| manifest.resource_package_id.as_deref())
                                .map(|id| id.eq_ignore_ascii_case(&config.resource_package_id))
                                .unwrap_or(false)
                        })
                    });
                    match owner {
                        Some(owner) if owner.plugin_id != config.plugin_id => checks.push(fail(
                            "RESOURCE_COLLISION_LIVE",
                            format!(
                                "{} is registered by {}",
                                config.resource_package_id, owner.plugin_id
                            ),
                        )),
                        Some(_) => checks.push(ok(
                            "RESOURCE_OWNER_LIVE",
                            "live resource ID belongs to this plugin",
                        )),
                        None => checks.push(ok(
                            "RESOURCE_FREE_LIVE",
                            "resource ID is free in the live registry",
                        )),
                    }
                }
                Err(error) => checks.push(fail(
                    "REGISTRY_PARSE",
                    format!("cannot parse live registry: {error:#}"),
                )),
            }
        } else {
            checks.push(warn(
                "REGISTRY_UNAVAILABLE",
                "live registry report is unavailable",
            ));
        }

        match sibling_configs(&context.termux_home) {
            Ok(siblings) => {
                for (root, sibling) in siblings {
                    if same_logical_path(&root, project) {
                        continue;
                    }
                    if sibling.plugin_id == config.plugin_id {
                        checks.push(fail(
                            "PLUGIN_ID_COLLISION",
                            format!("pluginId is duplicated by {}", root.display()),
                        ));
                    }
                    if sibling
                        .resource_package_id
                        .eq_ignore_ascii_case(&config.resource_package_id)
                    {
                        checks.push(fail(
                            "RESOURCE_COLLISION_PROJECT",
                            format!("resource ID is duplicated by {}", root.display()),
                        ));
                    }
                }
                if !checks.iter().any(|check| {
                    matches!(
                        check.code,
                        "PLUGIN_ID_COLLISION" | "RESOURCE_COLLISION_PROJECT"
                    )
                }) {
                    checks.push(ok(
                        "SIBLING_IDENTITIES",
                        "no sibling plugin identity/resource collision",
                    ));
                }
            }
            Err(error) => checks.push(fail("SIBLING_SCAN", format!("{error:#}"))),
        }

        let key = env::var_os("TERMUX_SHADOW_SIGNING_KEY_PKCS8");
        let key_id = env::var_os("TERMUX_SHADOW_SIGNING_KEY_ID");
        match (key, key_id) {
            (None, None) => checks.push(ok("SIGNING_DEBUG", "no release signing inputs requested")),
            (Some(path), Some(id))
                if PathBuf::from(&path).is_file() && !id.to_string_lossy().is_empty() =>
            {
                checks.push(ok("SIGNING_PAIRED", "release signing inputs are paired"));
            }
            _ => checks.push(fail(
                "SIGNING_INCOMPLETE",
                "signing key path and key ID must both be valid or both unset",
            )),
        }
    }

    if publish {
        if context.is_real_termux_home() {
            checks.push(ok(
                "PUBLISH_HOME",
                "publisher is inside the com.termux home",
            ));
        } else {
            checks.push(fail(
                "PUBLISH_HOME",
                format!(
                    "publishing is forbidden outside the com.termux home: {}",
                    context.termux_home.display()
                ),
            ));
        }
        let legacy = context.shadow_home.join("plugin-debug.zip");
        if legacy.exists() {
            checks.push(fail(
                "LEGACY_MANAGED_PACKAGE",
                format!("legacy fixed package exists: {}", legacy.display()),
            ));
        } else {
            checks.push(ok(
                "MANAGED_SINGLE_INGRESS",
                "managed home has no legacy fixed package",
            ));
        }
    }

    (Some(config), checks)
}

fn count(checks: &[Diagnostic], level: Level) -> usize {
    checks.iter().filter(|check| check.level == level).count()
}

fn print_human_checks(project: &Path, config: Option<&PluginConfig>, checks: &[Diagnostic]) {
    println!("Shadow plugin doctor");
    println!("  project: {}", project.display());
    if let Some(config) = config {
        println!("  pluginId: {}", config.plugin_id);
        println!("  partKey: {}", config.part_key);
        println!("  resource: {}\n", config.resource_package_id);
    }
    for check in checks {
        let label = match check.level {
            Level::Ok => "OK",
            Level::Warn => "WARN",
            Level::Fail => "FAIL",
        };
        println!("[{label:<4}] {}", check.message);
    }
}

fn emit_summary_or_json(
    context: &AppContext,
    project: &Path,
    config: Option<&PluginConfig>,
    checks: Vec<Diagnostic>,
    plugin_errors: usize,
    plugin_warnings: usize,
    package_validation: Option<PackageValidationReport>,
) -> Result<()> {
    if context.json {
        let output = DoctorOutput {
            ok: plugin_errors == 0,
            action: "doctor",
            project: project.display().to_string(),
            plugin_id: config.map(|value| value.plugin_id.clone()),
            resource_package_id: config.map(|value| value.resource_package_id.clone()),
            plugin_errors,
            plugin_warnings,
            checks,
            package_validation,
        };
        if context.verbose || output.package_validation.is_some() {
            println!("{}", serde_json::to_string_pretty(&output)?);
        } else {
            println!(
                "{}",
                serde_json::to_string(&serde_json::json!({
                    "ok": output.ok,
                    "status": if output.ok { "PASS" } else { "FAILED" },
                    "pluginId": output.plugin_id,
                    "resourcePackageId": output.resource_package_id,
                    "errors": output.plugin_errors,
                    "warnings": output.plugin_warnings,
                }))?
            );
        }
    } else {
        if let Some(validation) = &package_validation {
            println!("  result: {}", validation.status);
            if let Some(cache) = validation.cache {
                println!("  native cache: {cache}");
            }
            if let Some(artifact) = &validation.artifact {
                println!("  artifact: {artifact}");
            }
            if let Some(sha256) = &validation.sha256 {
                println!("  sha256: {sha256}");
            }
            if let Some(gradle) = &validation.gradle {
                build::print_gradle_summary(gradle, "  ");
            } else if validation.status == "PASS" {
                println!("  Gradle: not started (validated-artifact cache hit)");
                println!("  build/tool warnings: not re-evaluated on cache hit");
            }
            if let Some(error) = &validation.error {
                println!("  error: {error}");
            }
        }

        println!("\nDoctor summary");
        println!(
            "  plugin diagnostics: {} — {plugin_errors} error(s), {plugin_warnings} warning(s)",
            if plugin_errors == 0 { "PASS" } else { "FAIL" }
        );
        match &package_validation {
            Some(validation) => println!(
                "  package validation: {}{}",
                validation.status,
                validation
                    .cache
                    .map(|cache| format!(" (native cache {cache})"))
                    .unwrap_or_default()
            ),
            None => println!("  package validation: not requested (use --full)"),
        }
        if let Some(gradle) = package_validation
            .as_ref()
            .and_then(|validation| validation.gradle.as_ref())
        {
            println!(
                "  build/tool warnings: {} — separate from plugin diagnostics",
                gradle.warnings.len()
            );
        }
    }
    Ok(())
}

fn legacy_zip(project: &Path) -> bool {
    WalkDir::new(project)
        .follow_links(false)
        .into_iter()
        .filter_entry(|entry| {
            let relative = entry.path().strip_prefix(project).unwrap_or(entry.path());
            !matches!(
                relative
                    .components()
                    .next()
                    .and_then(|part| part.as_os_str().to_str()),
                Some(".gradle" | "build")
            ) && !relative.to_string_lossy().starts_with("plugin-app/build")
        })
        .filter_map(Result::ok)
        .any(|entry| entry.file_type().is_file() && entry.file_name() == "plugin-debug.zip")
}

fn android_library_plugin_check(
    project: &Path,
    plugin_marker_available: bool,
) -> Option<Diagnostic> {
    let root_groovy = project.join("build.gradle");
    let root_kotlin = project.join("build.gradle.kts");
    let root_text = [root_groovy.as_path(), root_kotlin.as_path()]
        .into_iter()
        .filter_map(|path| fs::read_to_string(path).ok())
        .collect::<Vec<_>>()
        .join("\n");
    let version_catalog =
        fs::read_to_string(project.join("gradle/libs.versions.toml")).unwrap_or_default();
    let settings_text = [
        project.join("settings.gradle"),
        project.join("settings.gradle.kts"),
    ]
    .into_iter()
    .filter_map(|path| fs::read_to_string(path).ok())
    .collect::<Vec<_>>()
    .join("\n");
    let plugin_mapping = settings_text.contains("com.android.library")
        && settings_text.contains("com.android.tools.build:gradle")
        && settings_text.contains("useModule");
    let root_declares_library = plugin_version_declared(&root_text, "com.android.library")
        || (version_catalog.contains("com.android.library")
            && version_catalog.lines().any(|line| {
                line.contains("com.android.library")
                    && (line.contains("version") || line.contains("version.ref"))
            }))
        || root_text.contains("com.android.tools.build:gradle:");

    let mut unresolved_modules = Vec::new();
    for entry in WalkDir::new(project)
        .follow_links(false)
        .into_iter()
        .filter_entry(|entry| include_doctor_entry(project, entry.path()))
        .filter_map(Result::ok)
    {
        if !entry.file_type().is_file()
            || entry.path() == root_groovy
            || entry.path() == root_kotlin
        {
            continue;
        }
        let name = entry.file_name().to_string_lossy();
        if !matches!(name.as_ref(), "build.gradle" | "build.gradle.kts") {
            continue;
        }
        let Ok(text) = fs::read_to_string(entry.path()) else {
            continue;
        };
        if text.contains("com.android.library")
            && !plugin_version_declared(&text, "com.android.library")
        {
            unresolved_modules.push(
                entry
                    .path()
                    .strip_prefix(project)
                    .unwrap_or(entry.path())
                    .display()
                    .to_string(),
            );
        }
    }
    if unresolved_modules.is_empty() {
        return None;
    }
    unresolved_modules.sort();
    if root_declares_library && (plugin_marker_available || plugin_mapping) {
        return Some(ok(
            "ANDROID_LIBRARY_PLUGIN_READY",
            format!(
                "Android Library plugin is versioned centrally for {}",
                unresolved_modules.join(", ")
            ),
        ));
    }

    let application_version = plugin_version(&root_text, "com.android.application");
    let declaration = application_version
        .map(|version| {
            format!("id 'com.android.library' version '{version}' apply false")
        })
        .unwrap_or_else(|| {
            "id 'com.android.library' version '<same cached AGP version as com.android.application>' apply false"
                .to_owned()
        });
    let mapping = "resolutionStrategy { eachPlugin { if (requested.id.id == 'com.android.library' && requested.version != null) useModule(\"com.android.tools.build:gradle:${requested.version}\") } }";
    if !root_declares_library {
        let mapping_fix = if !plugin_marker_available && !plugin_mapping {
            format!(
                " Also add `{mapping}` inside `pluginManagement` in settings.gradle so Gradle can reuse the AGP module when a plugin marker is absent."
            )
        } else {
            String::new()
        };
        return Some(fail(
            "ANDROID_LIBRARY_PLUGIN_UNDECLARED",
            format!(
                "{} applies com.android.library without a central version; add `{declaration}` to the root plugins block and keep module declarations versionless.{mapping_fix} Then rerun `shadow-plugin dev`",
                unresolved_modules.join(", ")
            ),
        ));
    }
    Some(fail(
        "ANDROID_LIBRARY_PLUGIN_MAPPING_MISSING",
        format!(
            "settings.gradle does not map com.android.library to the AGP module; add `{mapping}` inside `pluginManagement`, then rerun `shadow-plugin dev`"
        ),
    ))
}

fn compile_only_boundary_checks(project: &Path) -> Vec<Diagnostic> {
    let mut unsafe_declarations = Vec::new();
    for entry in WalkDir::new(project)
        .follow_links(false)
        .into_iter()
        .filter_entry(|entry| include_doctor_entry(project, entry.path()))
        .filter_map(Result::ok)
    {
        if !entry.file_type().is_file()
            || !matches!(
                entry.path().extension().and_then(|value| value.to_str()),
                Some("gradle" | "kts")
            )
        {
            continue;
        }
        let Ok(text) = fs::read_to_string(entry.path()) else {
            continue;
        };
        for (line_number, line) in text.lines().enumerate() {
            let trimmed = line.trim();
            if trimmed.starts_with("compileOnly")
                && !trimmed.contains("shadow-runtime.jar")
                && !trimmed.starts_with("//")
            {
                unsafe_declarations.push(format!(
                    "{}:{}: {}",
                    entry
                        .path()
                        .strip_prefix(project)
                        .unwrap_or(entry.path())
                        .display(),
                    line_number + 1,
                    trimmed
                ));
            }
        }
    }
    if unsafe_declarations.is_empty() {
        vec![ok(
            "DEPENDENCY_ABI_BOUNDARY",
            "only the Shadow runtime API is compileOnly; plugin-owned libraries are bundled",
        )]
    } else {
        vec![fail(
            "UNSAFE_COMPILE_ONLY_DEPENDENCY",
            format!(
                "external compileOnly dependencies are not a supported Host ABI; use implementation or an explicitly versioned Shadow ABI: {}",
                unsafe_declarations.join("; ")
            ),
        )]
    }
}

fn android_library_marker_available(context: &AppContext, project: &Path) -> bool {
    let root_text = [
        project.join("build.gradle"),
        project.join("build.gradle.kts"),
    ]
    .into_iter()
    .filter_map(|path| fs::read_to_string(path).ok())
    .collect::<Vec<_>>()
    .join("\n");
    let Some(version) = plugin_version(&root_text, "com.android.library")
        .or_else(|| plugin_version(&root_text, "com.android.application"))
    else {
        return false;
    };
    context.build_environment_for_project(project).is_ok_and(|environment| {
        std::iter::once(environment.gradle_home.as_path())
            .chain(environment.base_gradle_home.as_deref())
            .any(|home| {
                home.join(
                    "caches/modules-2/files-2.1/com.android.library/com.android.library.gradle.plugin",
                )
                .join(&version)
                .is_dir()
            })
    })
}

fn plugin_version_declared(text: &str, plugin_id: &str) -> bool {
    plugin_version(text, plugin_id).is_some()
}

fn plugin_version(text: &str, plugin_id: &str) -> Option<String> {
    let pattern = Regex::new(&format!(
        r#"id\s*\(?\s*['\"]{}['\"]\s*\)?\s*version\s*['\"]([^'\"]+)['\"]"#,
        regex::escape(plugin_id)
    ))
    .ok()?;
    pattern
        .captures(text)
        .and_then(|captures| captures.get(1))
        .map(|version| version.as_str().to_owned())
}

fn include_doctor_entry(project: &Path, path: &Path) -> bool {
    let relative = path.strip_prefix(project).unwrap_or(path);
    !relative.components().any(|component| {
        matches!(
            component.as_os_str().to_str(),
            Some(".git" | ".gradle" | "build" | "dist" | "out")
        )
    })
}

fn ok(code: &'static str, message: impl Into<String>) -> Diagnostic {
    Diagnostic {
        level: Level::Ok,
        code,
        message: message.into(),
    }
}

fn warn(code: &'static str, message: impl Into<String>) -> Diagnostic {
    Diagnostic {
        level: Level::Warn,
        code,
        message: message.into(),
    }
}

fn fail(code: &'static str, message: impl Into<String>) -> Diagnostic {
    Diagnostic {
        level: Level::Fail,
        code,
        message: message.into(),
    }
}

#[cfg(test)]
mod tests {
    use super::{
        Level, android_library_plugin_check, compile_only_boundary_checks, failed_checks_error,
    };
    use crate::errors::PreflightFailure;
    use std::fs;

    #[test]
    fn android_library_modules_require_a_central_plugin_version() {
        let root = tempfile::tempdir().unwrap();
        fs::write(
            root.path().join("build.gradle"),
            "plugins { id 'com.android.application' version '8.13.1' apply false }\n",
        )
        .unwrap();
        fs::create_dir(root.path().join("core-logic")).unwrap();
        fs::write(
            root.path().join("core-logic/build.gradle"),
            "plugins { id 'com.android.library' }\n",
        )
        .unwrap();

        let diagnostic = android_library_plugin_check(root.path(), false).unwrap();
        assert_eq!(diagnostic.level, Level::Fail);
        assert_eq!(diagnostic.code, "ANDROID_LIBRARY_PLUGIN_UNDECLARED");
        assert!(diagnostic.message.contains("8.13.1"));
        assert!(diagnostic.message.contains("shadow-plugin dev"));
        assert!(diagnostic.message.contains("useModule"));
        let error = failed_checks_error(std::slice::from_ref(&diagnostic));
        let typed = error.downcast_ref::<PreflightFailure>().unwrap();
        assert_eq!(typed.code, "ANDROID_LIBRARY_PLUGIN_UNDECLARED");
        assert_eq!(
            typed.diagnostics[0].file.as_deref(),
            Some("core-logic/build.gradle")
        );

        fs::write(
            root.path().join("build.gradle"),
            "plugins {\n  id 'com.android.application' version '8.13.1' apply false\n  id 'com.android.library' version '8.13.1' apply false\n}\n",
        )
        .unwrap();
        fs::write(
            root.path().join("settings.gradle"),
            "pluginManagement { resolutionStrategy { eachPlugin { if (requested.id.id == 'com.android.library') useModule(\"com.android.tools.build:gradle:${requested.version}\") } } }\n",
        )
        .unwrap();
        let diagnostic = android_library_plugin_check(root.path(), false).unwrap();
        assert_eq!(diagnostic.level, Level::Ok);
        assert_eq!(diagnostic.code, "ANDROID_LIBRARY_PLUGIN_READY");
    }

    #[test]
    fn external_compile_only_dependencies_are_rejected_at_the_host_abi_boundary() {
        let root = tempfile::tempdir().unwrap();
        fs::create_dir_all(root.path().join("plugin-app")).unwrap();
        fs::write(
            root.path().join("plugin-app/dependencies.gradle"),
            "dependencies {\n    compileOnly 'androidx.appcompat:appcompat:1.7.0'\n}\n",
        )
        .unwrap();
        let checks = compile_only_boundary_checks(root.path());
        assert_eq!(checks[0].level, Level::Fail);
        assert_eq!(checks[0].code, "UNSAFE_COMPILE_ONLY_DEPENDENCY");
    }
}
