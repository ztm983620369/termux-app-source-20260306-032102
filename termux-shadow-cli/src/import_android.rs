//! Conservative Android-module importer.  The source tree is never modified.

use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};
use regex::Regex;
use serde::Serialize;
use walkdir::WalkDir;

use crate::cli::{ImportAndroidArgs, NewArgs};
use crate::config::PluginConfig;
use crate::context::AppContext;
use crate::fsutil::copy_tree;
use crate::scaffold;

#[derive(Debug, Serialize, Default)]
#[serde(rename_all = "camelCase")]
struct MigrationReport {
    schema_version: u32,
    source: String,
    module: String,
    target: String,
    source_namespace: Option<String>,
    target_namespace: String,
    launcher_activity: Option<String>,
    copied_directories: Vec<String>,
    dependency_declarations: Vec<String>,
    detected_components: Vec<String>,
    migrated_configuration: Vec<String>,
    warnings: Vec<String>,
    errors: Vec<String>,
    state: String,
}

pub fn run(context: &AppContext, args: ImportAndroidArgs) -> Result<()> {
    scaffold::validate_slug(&args.slug)?;
    let source = canonical_directory(&args.source, "Android source")?;
    let module = detect_module(&source)?;
    let source_namespace = detect_namespace(&module);
    let target_namespace = args
        .namespace
        .clone()
        .unwrap_or_else(|| scaffold::namespace_for_slug(&args.slug));
    let target = resolve_target(context, &args.slug, args.target.as_deref());
    let manifest = read_manifest(&module);
    let launcher_activity = manifest.as_deref().and_then(find_launcher_activity);
    let launcher_source = launcher_activity
        .as_deref()
        .and_then(|launcher| find_component_source(&module, source_namespace.as_deref(), launcher));
    let dependencies = dependency_declarations(&module);
    let unsupported_dependencies = unsupported_dependency_declarations(&module);
    let components = detect_components(manifest.as_deref());
    let activity_count = manifest
        .as_deref()
        .map(|manifest| component_count(manifest, "activity"))
        .unwrap_or_default();
    let migrated_configuration = manifest
        .as_deref()
        .map(|manifest| {
            planned_manifest_configuration(
                manifest,
                source_namespace.as_deref(),
                &target_namespace,
                launcher_activity.as_deref(),
            )
        })
        .unwrap_or_default();
    let mut report = MigrationReport {
        schema_version: 1,
        source: source.display().to_string(),
        module: module.display().to_string(),
        target: target.display().to_string(),
        source_namespace: source_namespace.clone(),
        target_namespace: target_namespace.clone(),
        launcher_activity: launcher_activity.clone(),
        dependency_declarations: dependencies.clone(),
        detected_components: components,
        migrated_configuration: if args.dry_run {
            migrated_configuration
        } else {
            Vec::new()
        },
        state: "ANALYZED".to_owned(),
        ..MigrationReport::default()
    };
    if source_namespace.is_none() {
        report.warnings.push(
            "source namespace could not be inferred; package rewrites are limited".to_owned(),
        );
    }
    if launcher_activity.is_none() {
        report.errors.push(
            "no MAIN/LAUNCHER Activity could be resolved from AndroidManifest.xml".to_owned(),
        );
        report.state = "BLOCKED".to_owned();
    } else if launcher_source.is_none() {
        report.errors.push(format!(
            "launcher Activity source was not found under src/main/java or src/main/kotlin: {}",
            launcher_activity.as_deref().unwrap_or_default()
        ));
        report.state = "BLOCKED".to_owned();
    }
    if dependencies
        .iter()
        .any(|value| value.contains("androidx.appcompat"))
    {
        report.warnings.push(
            "AndroidX AppCompat detected; imported Activity code is rewritten to ShadowActivity"
                .to_owned(),
        );
    }
    for dependency in unsupported_dependencies {
        report.errors.push(format!(
            "dependency declaration cannot be migrated losslessly: {dependency}"
        ));
        report.state = "BLOCKED".to_owned();
    }
    if contains_kotlin_sources(&module) {
        report.errors.push(
            "Kotlin source is not yet supported by the Java-only Shadow template; the importer refuses to create a project that cannot compile deterministically"
                .to_owned(),
        );
        report.state = "BLOCKED".to_owned();
    }
    for component in &report.detected_components {
        if component == "application" {
            report.errors.push(
                "custom Application detected; ShadowApplication lifecycle compatibility must be implemented before import"
                    .to_owned(),
            );
            report.state = "BLOCKED".to_owned();
        } else if component != "activity" {
            report.errors.push(format!(
                "{component} detected; the default Shadow manifest cannot preserve this component losslessly"
            ));
            report.state = "BLOCKED".to_owned();
        }
    }
    if activity_count > 1 {
        report.errors.push(format!(
            "{activity_count} Activity declarations detected; only a single launcher Activity can currently be migrated losslessly"
        ));
        report.state = "BLOCKED".to_owned();
    }
    if args.dry_run {
        return emit(context, &report);
    }
    if !report.errors.is_empty() {
        bail!(
            "ANDROID_IMPORT_REVIEW_REQUIRED: {}; run import-android with --dry-run for the complete migration report",
            report.errors.join("; ")
        );
    }
    if target.exists() {
        bail!("target already exists: {}", target.display());
    }
    let parent = target.parent().context("import target has no parent")?;
    fs::create_dir_all(parent)?;
    let name = target
        .file_name()
        .context("import target has no name")?
        .to_string_lossy();
    let staging = parent.join(format!(".{name}.import.{}", std::process::id()));
    if staging.exists() {
        fs::remove_dir_all(&staging)?;
    }

    // Reuse normal identity allocation and template validation, but stage everything first.
    scaffold::run_silent(
        context,
        NewArgs {
            slug: args.slug.clone(),
            display_name: args.display_name.clone(),
            target: Some(staging.clone()),
            plugin_id: args.plugin_id.clone(),
            part_key: args.part_key.clone(),
            namespace: Some(target_namespace.clone()),
            activity: None,
            resource_id: args.resource_id.clone(),
            description: Some(format!("Imported Android project: {}", source.display())),
            publish: false,
            allow_existing: false,
            dry_run: false,
        },
    )?;

    let result = (|| -> Result<()> {
        apply_manifest_configuration(
            &staging,
            manifest.as_deref(),
            source_namespace.as_deref(),
            &target_namespace,
            launcher_activity.as_deref(),
            &mut report,
        )?;
        copy_module_sources(
            &module,
            &staging,
            source_namespace.as_deref(),
            &target_namespace,
            &mut report,
        )?;
        replace_generated_launcher(
            &staging,
            source_namespace.as_deref(),
            &target_namespace,
            launcher_activity.as_deref(),
            launcher_source.as_deref(),
            &mut report,
        )?;
        append_dependencies(&staging, &dependencies, &mut report)?;
        let inherited_smoke = staging.join("shadow-smoke.json");
        if inherited_smoke.is_file() {
            fs::remove_file(&inherited_smoke)?;
            report.warnings.push(
                "template UI smoke spec was removed; create one using imported view IDs after reviewing the migration"
                    .to_owned(),
            );
        }
        report.state = "READY".to_owned();
        write_report(&staging, &report)?;
        Ok(())
    })();
    if let Err(error) = result {
        report.state = "FAILED".to_owned();
        report.errors.push(format!("{error:#}"));
        let _ = write_report(&staging, &report);
        let _ = fs::remove_dir_all(&staging);
        return Err(error);
    }
    fs::rename(&staging, &target).with_context(|| {
        format!(
            "atomically commit imported Android project {} to {}",
            staging.display(),
            target.display()
        )
    })?;
    report.target = target.display().to_string();
    if context.json {
        emit(context, &report)?;
    } else {
        println!("Imported Android module into {}", target.display());
        println!(
            "Migration report: {}",
            target.join("shadow-import-report.json").display()
        );
        for warning in &report.warnings {
            println!("  warning: {warning}");
        }
    }
    Ok(())
}

fn planned_manifest_configuration(
    manifest: &str,
    old_namespace: Option<&str>,
    new_namespace: &str,
    launcher: Option<&str>,
) -> Vec<String> {
    let mut entries = Vec::new();
    if let Some(application) = opening_tag_attrs(manifest, "application") {
        if let Some(class_name) = attr(&application, "name")
            && let Some(class_name) =
                resolve_component_class(&class_name, old_namespace, new_namespace)
        {
            entries.push(format!("applicationClassName={class_name}"));
        }
        if let Some(theme) = attr(&application, "theme").and_then(normalize_theme) {
            entries.push(format!("applicationTheme={theme}"));
        }
    }
    if let Some(activity) = launcher.and_then(|launcher| activity_attrs(manifest, launcher)) {
        if let Some(theme) = attr(&activity, "theme").and_then(normalize_theme) {
            entries.push(format!("activityTheme={theme}"));
        }
        for (attribute, label) in [
            ("screenOrientation", "screenOrientation"),
            ("windowSoftInputMode", "softInputMode"),
            ("configChanges", "configChanges"),
        ] {
            if let Some(value) = attr(&activity, attribute) {
                entries.push(format!("{label}={value}"));
            }
        }
    }
    entries.sort();
    entries.dedup();
    entries
}

fn apply_manifest_configuration(
    target: &Path,
    manifest: Option<&str>,
    old_namespace: Option<&str>,
    new_namespace: &str,
    launcher: Option<&str>,
    report: &mut MigrationReport,
) -> Result<()> {
    let Some(manifest) = manifest else {
        return Ok(());
    };
    let path = target.join("shadow-plugin.properties");
    let mut config = PluginConfig::load(&path)?;
    if let Some(application) = opening_tag_attrs(manifest, "application") {
        if let Some(class_name) = attr(&application, "name")
            && let Some(class_name) =
                resolve_component_class(&class_name, old_namespace, new_namespace)
        {
            migrate_config_value(
                &mut config,
                report,
                "applicationClassName",
                class_name,
                |candidate, value| candidate.application_class_name = Some(value.to_owned()),
            );
        }
        if let Some(theme) = attr(&application, "theme").and_then(normalize_theme) {
            migrate_config_value(
                &mut config,
                report,
                "applicationTheme",
                theme,
                |candidate, value| candidate.application_theme = value.to_owned(),
            );
        }
    }
    if let Some(activity) = launcher.and_then(|launcher| activity_attrs(manifest, launcher)) {
        if let Some(theme) = attr(&activity, "theme").and_then(normalize_theme) {
            migrate_config_value(
                &mut config,
                report,
                "activityTheme",
                theme,
                |candidate, value| candidate.activity_theme = value.to_owned(),
            );
        }
        for (attribute, label) in [
            ("screenOrientation", "screenOrientation"),
            ("windowSoftInputMode", "softInputMode"),
            ("configChanges", "configChanges"),
        ] {
            let Some(value) = attr(&activity, attribute) else {
                continue;
            };
            migrate_config_value(
                &mut config,
                report,
                label,
                value,
                |candidate, value| match label {
                    "screenOrientation" => candidate.screen_orientation = value.to_owned(),
                    "softInputMode" => candidate.soft_input_mode = value.to_owned(),
                    "configChanges" => candidate.config_changes = value.to_owned(),
                    _ => unreachable!(),
                },
            );
        }
    }
    config.write(&path)
}

fn migrate_config_value(
    config: &mut PluginConfig,
    report: &mut MigrationReport,
    label: &str,
    value: String,
    apply: impl FnOnce(&mut PluginConfig, &str),
) {
    let mut candidate = config.clone();
    apply(&mut candidate, &value);
    let validation = candidate.validate();
    if validation.is_empty() {
        *config = candidate;
        report
            .migrated_configuration
            .push(format!("{label}={value}"));
    } else {
        report.warnings.push(format!(
            "skipped unsupported {label}={value}: {}",
            validation.join("; ")
        ));
    }
}

fn opening_tag_attrs(manifest: &str, tag: &str) -> Option<String> {
    Regex::new(&format!(r#"(?is)<{tag}\b([^>]*)>"#))
        .ok()?
        .captures(manifest)
        .and_then(|captures| captures.get(1).map(|value| value.as_str().to_owned()))
}

fn activity_attrs(manifest: &str, launcher: &str) -> Option<String> {
    let regex = Regex::new(r#"(?is)<activity\b([^>]*)>"#).ok()?;
    regex.captures_iter(manifest).find_map(|captures| {
        let attrs = captures.get(1)?.as_str();
        (attr(attrs, "name").as_deref() == Some(launcher)).then(|| attrs.to_owned())
    })
}

fn resolve_component_class(
    value: &str,
    old_namespace: Option<&str>,
    new_namespace: &str,
) -> Option<String> {
    let resolved = if let Some(relative) = value.strip_prefix('.') {
        format!("{}.{}", old_namespace?, relative)
    } else if value.contains('.') {
        value.to_owned()
    } else {
        format!("{}.{}", old_namespace?, value)
    };
    Some(if let Some(old_namespace) = old_namespace {
        match resolved.strip_prefix(old_namespace) {
            Some(suffix) if suffix.is_empty() || suffix.starts_with('.') => {
                format!("{new_namespace}{suffix}")
            }
            _ => resolved,
        }
    } else {
        resolved
    })
}

fn normalize_theme(value: String) -> Option<String> {
    if let Some(name) = value.strip_prefix("@android:style/") {
        return Some(format!("android.R.style.{}", name.replace('.', "_")));
    }
    (value.starts_with("@style/") || value.starts_with("android.R.style.")).then_some(value)
}

fn canonical_directory(path: &Path, label: &str) -> Result<PathBuf> {
    let path =
        fs::canonicalize(path).with_context(|| format!("resolve {label} {}", path.display()))?;
    if path.is_dir() {
        Ok(path)
    } else {
        bail!("{label} is not a directory: {}", path.display())
    }
}

fn detect_module(source: &Path) -> Result<PathBuf> {
    if source.join("src/main").is_dir() {
        return Ok(source.to_path_buf());
    }
    if source.join("app/src/main").is_dir() {
        return Ok(source.join("app"));
    }
    let mut candidates = fs::read_dir(source)?
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| path.join("src/main").is_dir())
        .collect::<Vec<_>>();
    candidates.sort();
    candidates.into_iter().next().with_context(|| {
        format!(
            "no Android module with src/main found under {}",
            source.display()
        )
    })
}

fn detect_namespace(module: &Path) -> Option<String> {
    let manifest = read_manifest(module)?;
    let package = Regex::new(r#"(?m)\bpackage\s*=\s*[\"']([^\"']+)[\"']"#)
        .ok()?
        .captures(&manifest)
        .and_then(|captures| captures.get(1).map(|value| value.as_str().to_owned()));
    package.or_else(|| {
        ["build.gradle", "build.gradle.kts"]
            .into_iter()
            .map(|name| module.join(name))
            .filter_map(|path| fs::read_to_string(path).ok())
            .find_map(|text| {
                Regex::new(r#"(?m)\bnamespace\s*(?:=\s*)?[\"']([^\"']+)[\"']"#)
                    .ok()?
                    .captures(&text)
                    .and_then(|captures| captures.get(1).map(|value| value.as_str().to_owned()))
            })
    })
}

fn read_manifest(module: &Path) -> Option<String> {
    fs::read_to_string(module.join("src/main/AndroidManifest.xml")).ok()
}

fn contains_kotlin_sources(module: &Path) -> bool {
    let root = module.join("src/main");
    root.is_dir()
        && WalkDir::new(root)
            .follow_links(false)
            .into_iter()
            .filter_map(Result::ok)
            .any(|entry| {
                entry.file_type().is_file()
                    && entry.path().extension().and_then(|value| value.to_str()) == Some("kt")
            })
}

fn find_launcher_activity(manifest: &str) -> Option<String> {
    for tag in ["activity", "activity-alias"] {
        let component_re = Regex::new(&format!(r#"(?is)<{tag}\s+([^>]*)>(.*?)</{tag}>"#)).ok()?;
        for captures in component_re.captures_iter(manifest) {
            let body = captures.get(2)?.as_str();
            if body.contains("android.intent.action.MAIN")
                && body.contains("android.intent.category.LAUNCHER")
            {
                let attributes = captures.get(1)?.as_str();
                return if tag == "activity-alias" {
                    attr(attributes, "targetActivity")
                } else {
                    attr(attributes, "name")
                };
            }
        }
    }
    None
}

fn find_component_source(
    module: &Path,
    namespace: Option<&str>,
    component: &str,
) -> Option<PathBuf> {
    let class_name = if let Some(relative) = component.strip_prefix('.') {
        format!("{}.{}", namespace?, relative)
    } else if component.contains('.') {
        component.to_owned()
    } else {
        format!("{}.{}", namespace?, component)
    };
    let relative = class_name.replace('.', "/");
    for (directory, extension) in [("java", "java"), ("kotlin", "kt")] {
        let path = module
            .join("src/main")
            .join(directory)
            .join(format!("{relative}.{extension}"));
        if path.is_file() {
            return Some(path);
        }
    }

    // Java and Kotlin do not require the filesystem path to mirror the package. Accept a unique
    // package/class match, but never guess between same-named classes.
    let simple_name = class_name.rsplit('.').next()?;
    let package_name = class_name.strip_suffix(&format!(".{simple_name}"))?;
    let package_re = Regex::new(r"(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*;?").ok()?;
    let mut matches = Vec::new();
    for directory in ["java", "kotlin"] {
        let root = module.join("src/main").join(directory);
        if !root.is_dir() {
            continue;
        }
        for entry in WalkDir::new(root)
            .follow_links(false)
            .into_iter()
            .filter_map(Result::ok)
        {
            if !entry.file_type().is_file()
                || entry.path().file_stem().and_then(|value| value.to_str()) != Some(simple_name)
            {
                continue;
            }
            let Ok(text) = fs::read_to_string(entry.path()) else {
                continue;
            };
            let declared_package = package_re
                .captures(&text)
                .and_then(|captures| captures.get(1).map(|value| value.as_str()));
            if declared_package == Some(package_name) {
                matches.push(entry.path().to_path_buf());
            }
        }
    }
    matches.sort();
    (matches.len() == 1).then(|| matches.remove(0))
}

fn attr(attrs: &str, name: &str) -> Option<String> {
    Regex::new(&format!(
        r#"\b(?:android:)?{name}\s*=\s*[\"']([^\"']+)[\"']"#
    ))
    .ok()?
    .captures(attrs)
    .and_then(|captures| captures.get(1).map(|value| value.as_str().to_owned()))
}

fn detect_components(manifest: Option<&str>) -> Vec<String> {
    let Some(manifest) = manifest else {
        return Vec::new();
    };
    let mut components = ["activity", "service", "receiver", "provider"]
        .into_iter()
        .filter(|name| {
            Regex::new(&format!(r"(?i)<{name}\b"))
                .map(|regex| regex.is_match(manifest))
                .unwrap_or(false)
        })
        .map(str::to_owned)
        .collect::<Vec<_>>();
    if opening_tag_attrs(manifest, "application")
        .as_deref()
        .and_then(|attributes| attr(attributes, "name"))
        .is_some()
    {
        components.push("application".to_owned());
    }
    components
}

fn component_count(manifest: &str, component: &str) -> usize {
    Regex::new(&format!(r"(?i)<{component}\s"))
        .map(|regex| regex.find_iter(manifest).count())
        .unwrap_or_default()
}

fn dependency_declarations(module: &Path) -> Vec<String> {
    let text = ["build.gradle", "build.gradle.kts"]
        .into_iter()
        .filter_map(|name| fs::read_to_string(module.join(name)).ok())
        .collect::<Vec<_>>()
        .join("\n");
    let Ok(regex) = Regex::new(
        r#"(?m)\b(?:implementation|api|compileOnly|runtimeOnly|kapt)\s*(?:\(\s*)?[\"']([^\"']+)[\"']"#,
    ) else {
        return Vec::new();
    };
    let mut values = regex
        .captures_iter(&text)
        .filter_map(|captures| captures.get(1).map(|value| value.as_str().to_owned()))
        .filter(|value| !value.starts_with("project(") && !value.starts_with("files("))
        .collect::<Vec<_>>();
    values.sort();
    values.dedup();
    values
}

fn unsupported_dependency_declarations(module: &Path) -> Vec<String> {
    let Ok(configuration) = Regex::new(
        r"^((?:[A-Za-z_][A-Za-z0-9_]*(?:Implementation|Api|CompileOnly|RuntimeOnly|AnnotationProcessor))|implementation|api|compileOnly|runtimeOnly|annotationProcessor|kapt|ksp|compile|provided|runtime|coreLibraryDesugaring|lintChecks|lintPublish|wearApp|add|constraints)\b(.*)$",
    ) else {
        return Vec::new();
    };
    let Ok(concrete_coordinate) =
        Regex::new(r#"^(?:\(\s*)?[\"'][^\"'$]+:[^\"'$]+:[^\"'$]+[\"']\s*\)?\s*$"#)
    else {
        return Vec::new();
    };
    let mut unsupported = Vec::new();
    for name in ["build.gradle", "build.gradle.kts"] {
        let path = module.join(name);
        let Ok(text) = fs::read_to_string(&path) else {
            continue;
        };
        for (index, line) in text.lines().enumerate() {
            let statement = line.trim();
            if statement.is_empty() || statement.starts_with("//") {
                continue;
            }
            let Some(captures) = configuration.captures(statement) else {
                continue;
            };
            let kind = captures.get(1).map(|value| value.as_str()).unwrap_or("");
            let expression = captures
                .get(2)
                .map(|value| value.as_str())
                .unwrap_or("")
                .trim();
            // The target is an Android application with a strict host ABI. Reclassifying API,
            // compile-only, or runtime-only edges as implementation would silently change the
            // dependency graph, so only concrete implementation coordinates are lossless here.
            let supported_kind = kind == "implementation";
            if supported_kind && concrete_coordinate.is_match(expression) {
                continue;
            }
            let bounded = statement.chars().take(240).collect::<String>();
            unsupported.push(format!("{name}:{}: {bounded}", index + 1));
        }
    }
    unsupported.sort();
    unsupported.dedup();
    unsupported.truncate(32);
    unsupported
}

fn copy_module_sources(
    module: &Path,
    target: &Path,
    old_namespace: Option<&str>,
    new_namespace: &str,
    report: &mut MigrationReport,
) -> Result<()> {
    let source_root = module.join("src/main");
    let target_root = target.join("plugin-app/src/main");
    for directory in ["java", "kotlin", "res", "assets", "aidl", "jniLibs"] {
        let source = source_root.join(directory);
        if !source.is_dir() {
            continue;
        }
        let destination = target_root.join(directory);
        copy_tree(&source, &destination)?;
        rewrite_tree(&destination, old_namespace, new_namespace)?;
        report.copied_directories.push(directory.to_owned());
    }
    Ok(())
}

fn replace_generated_launcher(
    target: &Path,
    old_namespace: Option<&str>,
    new_namespace: &str,
    launcher: Option<&str>,
    source_file: Option<&Path>,
    report: &mut MigrationReport,
) -> Result<()> {
    let Some(launcher) = launcher else {
        report
            .warnings
            .push("no launcher Activity found; scaffold Activity remains the entry point".into());
        return Ok(());
    };
    let simple = launcher.rsplit('.').next().unwrap_or(launcher);
    let source_file = source_file.context("preflight launcher source disappeared during import")?;
    let generated = find_generated_activity(target)?;
    let mut text = fs::read_to_string(source_file)?;
    if let Some(old_namespace) = old_namespace {
        text = text.replace(old_namespace, new_namespace);
    }
    text = rewrite_shadow_activity(&text);
    let new_simple = generated
        .file_stem()
        .and_then(|value| value.to_str())
        .unwrap_or(simple);
    if simple != new_simple {
        text = text.replace(simple, new_simple);
    }
    fs::write(generated, text)?;
    report.warnings.push(format!(
        "launcher Activity {launcher} was transplanted into the generated Shadow entry class"
    ));
    Ok(())
}

fn find_generated_activity(target: &Path) -> Result<PathBuf> {
    let config = PluginConfig::load(&target.join("shadow-plugin.properties"))?;
    let relative = format!("{}.java", config.activity_class_name.replace('.', "/"));
    let path = target.join("plugin-app/src/main/java").join(relative);
    path.is_file()
        .then_some(path)
        .context("generated scaffold Activity not found")
}

fn rewrite_tree(root: &Path, old_namespace: Option<&str>, new_namespace: &str) -> Result<()> {
    for entry in WalkDir::new(root)
        .follow_links(false)
        .into_iter()
        .filter_map(Result::ok)
    {
        if !entry.file_type().is_file() {
            continue;
        }
        let Some(extension) = entry.path().extension().and_then(|value| value.to_str()) else {
            continue;
        };
        if !matches!(extension, "java" | "kt" | "xml") {
            continue;
        }
        let text = fs::read_to_string(entry.path())?;
        let mut rewritten = text.clone();
        if let Some(old_namespace) = old_namespace {
            rewritten = rewritten.replace(old_namespace, new_namespace);
        }
        rewritten = rewrite_shadow_activity(&rewritten);
        if rewritten != text {
            fs::write(entry.path(), rewritten)?;
        }
    }
    Ok(())
}

fn rewrite_shadow_activity(text: &str) -> String {
    let rewritten = text
        .replace(
            "androidx.appcompat.app.AppCompatActivity",
            "com.tencent.shadow.core.runtime.ShadowActivity",
        )
        .replace(
            "android.app.Activity",
            "com.tencent.shadow.core.runtime.ShadowActivity",
        )
        .replace("extends AppCompatActivity", "extends ShadowActivity")
        .replace("extends Activity", "extends ShadowActivity");
    if rewritten.contains("ShadowActivity") {
        widen_shadow_callback_visibility(&rewritten)
    } else {
        rewritten
    }
}

fn widen_shadow_callback_visibility(text: &str) -> String {
    text.split_inclusive('\n')
        .map(|line| {
            let statement = line.trim_start();
            let Some(signature) = statement.strip_prefix("protected ") else {
                return line.to_owned();
            };
            let Some(before_arguments) = signature.split_once('(').map(|value| value.0) else {
                return line.to_owned();
            };
            let callback = before_arguments.split_whitespace().last().unwrap_or("");
            if callback.starts_with("on")
                && callback
                    .chars()
                    .nth(2)
                    .is_some_and(|character| character.is_ascii_uppercase())
            {
                line.replacen("protected", "public", 1)
            } else {
                line.to_owned()
            }
        })
        .collect()
}

fn append_dependencies(
    target: &Path,
    dependencies: &[String],
    report: &mut MigrationReport,
) -> Result<()> {
    if dependencies.is_empty() {
        return Ok(());
    }
    let path = target.join("plugin-app/dependencies.gradle");
    let mut text = String::from(
        "// Dependencies imported by shadow-plugin import-android. Review the migration report.\ndependencies {\n",
    );
    for dependency in dependencies {
        if dependency.contains('$') {
            report.warnings.push(format!(
                "dynamic dependency {dependency} was not copied; replace its source variable with a locked version"
            ));
            continue;
        }
        if dependency.contains("androidx.appcompat") {
            report.warnings.push(format!(
                "dependency {dependency} is bundled for widgets/resources; Activity inheritance was rewritten and AppCompat-only APIs still require compatibility review"
            ));
            text.push_str(&format!("    implementation '{dependency}'\n"));
        } else {
            text.push_str(&format!("    implementation '{dependency}'\n"));
        }
    }
    text.push_str("}\n");
    fs::write(path, text)?;
    Ok(())
}

fn write_report(target: &Path, report: &MigrationReport) -> Result<()> {
    fs::write(
        target.join("shadow-import-report.json"),
        serde_json::to_vec_pretty(report)?,
    )?;
    Ok(())
}

fn resolve_target(context: &AppContext, slug: &str, requested: Option<&Path>) -> PathBuf {
    requested
        .map(Path::to_path_buf)
        .unwrap_or_else(|| context.termux_home.join(format!("termux-shadow-{slug}")))
}

fn emit(context: &AppContext, report: &MigrationReport) -> Result<()> {
    if context.json {
        println!("{}", serde_json::to_string_pretty(report)?);
    } else {
        println!("Android import {}", report.state);
        println!("  source: {}", report.source);
        println!("  module: {}", report.module);
        println!("  target: {}", report.target);
        if let Some(namespace) = &report.source_namespace {
            println!("  source namespace: {namespace}");
        }
        println!("  target namespace: {}", report.target_namespace);
        for warning in &report.warnings {
            println!("  warning: {warning}");
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{
        component_count, contains_kotlin_sources, detect_components, detect_module,
        detect_namespace, find_launcher_activity, normalize_theme, resolve_component_class,
        unsupported_dependency_declarations, widen_shadow_callback_visibility,
    };
    use std::fs;

    #[test]
    fn detects_standard_app_module_namespace_and_launcher() {
        let root = tempfile::tempdir().unwrap();
        let module = root.path().join("app");
        fs::create_dir_all(module.join("src/main")).unwrap();
        fs::write(
            module.join("build.gradle"),
            "android { namespace 'com.example.chat' }\n",
        )
        .unwrap();
        let manifest = r#"<manifest><application><activity android:name=".MainActivity"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>"#;
        fs::write(module.join("src/main/AndroidManifest.xml"), manifest).unwrap();
        assert_eq!(detect_module(root.path()).unwrap(), module);
        assert_eq!(
            detect_namespace(&module).as_deref(),
            Some("com.example.chat")
        );
        assert_eq!(
            find_launcher_activity(manifest).as_deref(),
            Some(".MainActivity")
        );
        assert_eq!(detect_components(Some(manifest)), vec!["activity"]);
        assert_eq!(component_count(manifest, "activity"), 1);
        assert!(find_launcher_activity(r#"<manifest><application><activity android:name=".NotALauncher" /></application></manifest>"#).is_none());
        assert_eq!(
            find_launcher_activity(
                r#"<manifest><application><activity android:name=".MainActivity"/><activity-alias android:name=".Launcher" android:targetActivity=".MainActivity"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity-alias></application></manifest>"#
            )
            .as_deref(),
            Some(".MainActivity")
        );
    }

    #[test]
    fn rewrites_only_classes_inside_the_source_namespace() {
        assert_eq!(
            resolve_component_class(
                "com.example.chat.MainActivity",
                Some("com.example.chat"),
                "com.termux.shadow.chat"
            )
            .as_deref(),
            Some("com.termux.shadow.chat.MainActivity")
        );
        assert_eq!(
            resolve_component_class(
                "org.vendor.com.example.chat.ExternalActivity",
                Some("com.example.chat"),
                "com.termux.shadow.chat"
            )
            .as_deref(),
            Some("org.vendor.com.example.chat.ExternalActivity")
        );
    }

    #[test]
    fn detects_kotlin_before_creating_a_broken_java_template() {
        let root = tempfile::tempdir().unwrap();
        let source = root.path().join("src/main/kotlin/com/example");
        fs::create_dir_all(&source).unwrap();
        fs::write(source.join("MainActivity.kt"), "class MainActivity\n").unwrap();
        assert!(contains_kotlin_sources(root.path()));
    }

    #[test]
    fn converts_framework_style_resource_names_to_java_fields() {
        assert_eq!(
            normalize_theme("@android:style/Theme.Material.Light.NoActionBar".into()).as_deref(),
            Some("android.R.style.Theme_Material_Light_NoActionBar")
        );
    }

    #[test]
    fn blocks_project_file_and_dynamic_dependency_syntax() {
        let root = tempfile::tempdir().unwrap();
        fs::write(
            root.path().join("build.gradle"),
            "dependencies {\n    implementation 'androidx.core:core:1.13.1'\n    implementation project(':core')\n    implementation files('libs/local.jar')\n    implementation \"io.example:dynamic:$version\"\n    compileOnly 'io.example:annotations:1.0'\n    freeImplementation 'io.example:flavor:1.0'\n    add('implementation', 'io.example:add:1.0')\n}\n",
        )
        .unwrap();
        let unsupported = unsupported_dependency_declarations(root.path());
        assert_eq!(unsupported.len(), 6);
        assert!(unsupported.iter().any(|value| value.contains("project")));
        assert!(unsupported.iter().any(|value| value.contains("local.jar")));
        assert!(unsupported.iter().any(|value| value.contains("$version")));
        assert!(
            unsupported
                .iter()
                .any(|value| value.contains("compileOnly"))
        );
        assert!(
            unsupported
                .iter()
                .any(|value| value.contains("freeImplementation"))
        );
        assert!(unsupported.iter().any(|value| value.contains("add(")));
    }

    #[test]
    fn widens_only_shadow_style_protected_callbacks() {
        let source =
            "    protected void onCreate(Bundle state) {}\n    protected void helper() {}\n";
        assert_eq!(
            widen_shadow_callback_visibility(source),
            "    public void onCreate(Bundle state) {}\n    protected void helper() {}\n"
        );
    }
}
