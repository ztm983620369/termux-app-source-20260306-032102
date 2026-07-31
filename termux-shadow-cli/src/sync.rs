use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};
use regex::Regex;
use serde::Serialize;
use walkdir::WalkDir;

use crate::cli::{DoctorArgs, SyncArgs};
use crate::config::{PluginConfig, parse_properties};
use crate::context::{AppContext, same_logical_path};
use crate::doctor;
use crate::fsutil::write_atomic;

const ROOT_TOOLING: [&str; 8] = [
    "README.md",
    "build.gradle",
    "settings.gradle",
    "gradle.properties",
    "gradlew",
    "gradlew.bat",
    "shadow-plugin",
    "plugin-app/build.gradle",
];
const TOOLING_DIRECTORIES: [&str; 3] = ["gradle", "scripts", "shadow"];
pub const SYNC_MARKER: &str = ".shadow-tooling-sync-incomplete.json";

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct SyncOutput {
    project: String,
    template: String,
    dry_run: bool,
    changed: Vec<String>,
    unchanged: usize,
    preserved: [&'static str; 4],
}

pub fn run(context: &AppContext, args: SyncArgs) -> Result<()> {
    let project = context.project()?;
    let template = context.template()?;
    if same_logical_path(&project, &template) {
        bail!("current project is the installed template; no sync is required");
    }
    let files = tooling_files(&template)?;
    let mut changed = Vec::new();
    let mut unchanged = 0usize;
    let marker = project.join(SYNC_MARKER);
    if !args.dry_run {
        let marker_value = serde_json::json!({
            "schemaVersion": 1,
            "project": project.display().to_string(),
            "template": template.display().to_string(),
            "pid": std::process::id(),
        });
        let mut bytes = serde_json::to_vec_pretty(&marker_value)?;
        bytes.push(b'\n');
        write_atomic(&marker, &bytes)?;
    }
    let update_result = (|| -> Result<()> {
        for source in files {
            let relative = source.strip_prefix(&template)?;
            let destination = project.join(relative);
            let source_bytes = fs::read(&source)?;
            if fs::read(&destination).ok().as_deref() == Some(source_bytes.as_slice()) {
                unchanged += 1;
                continue;
            }
            changed.push(relative.display().to_string());
            if args.dry_run {
                continue;
            }
            if relative == Path::new("plugin-app/build.gradle") {
                preserve_module_customizations(&project)?;
            }
            write_atomic(&destination, &source_bytes)
                .with_context(|| format!("sync {}", relative.display()))?;
            copy_permissions(&source, &destination)?;
        }
        if migrate_config_schema(&project, args.dry_run)? {
            changed.push("shadow-plugin.properties (schema migration)".to_owned());
        }
        Ok(())
    })();
    update_result?;
    if !args.dry_run && marker.exists() {
        fs::remove_file(&marker)
            .with_context(|| format!("complete tooling sync {}", marker.display()))?;
    }

    let output = SyncOutput {
        project: project.display().to_string(),
        template: template.display().to_string(),
        dry_run: args.dry_run,
        changed,
        unchanged,
        preserved: [
            "plugin identity values",
            "plugin-app/src/ business source",
            "plugin-app/dependencies.gradle",
            "project .gitignore",
        ],
    };
    if !args.dry_run && context.json {
        doctor::validate_for_project(context, &project, true, false)?;
    }
    if context.json {
        println!("{}", serde_json::to_string_pretty(&output)?);
    } else {
        println!("Shadow tooling sync");
        println!("  project: {}", output.project);
        println!("  template: {}", output.template);
        println!("  mode: {}", if args.dry_run { "dry-run" } else { "write" });
        if output.changed.is_empty() {
            println!("  changed: none");
        } else {
            println!("  changed: {} file(s)", output.changed.len());
            for path in &output.changed {
                println!("    {path}");
            }
        }
        println!("  unchanged: {} file(s)", output.unchanged);
        println!(
            "  preserved: plugin identity values, plugin-app/src/, plugin-app/dependencies.gradle, project .gitignore"
        );
    }

    if !args.dry_run && !context.json {
        doctor::run_for_project(
            context,
            &project,
            DoctorArgs {
                workspace: false,
                project_only: true,
                publish: false,
                full: false,
                fresh: false,
                failures_only: false,
            },
        )?;
    }
    Ok(())
}

fn preserve_module_customizations(project: &Path) -> Result<()> {
    let build_file = project.join("plugin-app/build.gradle");
    let Ok(text) = fs::read_to_string(&build_file) else {
        return Ok(());
    };
    let backup = project.join("plugin-app/build.gradle.pre-shadow-sync");
    if !backup.exists() {
        write_atomic(&backup, text.as_bytes())?;
    }
    let dependency_line = Regex::new(
        r"^(?:implementation|api|compileOnly|runtimeOnly|annotationProcessor|kapt|ksp)\b.+$",
    )?;
    let mut declarations = text
        .lines()
        .map(str::trim)
        .filter(|line| dependency_line.is_match(line))
        .filter(|line| !line.contains("shadow-runtime.jar"))
        .map(str::to_owned)
        .collect::<Vec<_>>();
    let dependencies_file = project.join("plugin-app/dependencies.gradle");
    if let Ok(existing) = fs::read_to_string(&dependencies_file) {
        declarations.extend(
            existing
                .lines()
                .map(str::trim)
                .filter(|line| dependency_line.is_match(line))
                .map(str::to_owned),
        );
    }
    declarations.sort();
    declarations.dedup();
    if declarations.is_empty() {
        return Ok(());
    }
    let mut output = String::from(
        "// Project-owned dependencies preserved by shadow-plugin sync.\ndependencies {\n",
    );
    for declaration in declarations {
        output.push_str("    ");
        output.push_str(&declaration);
        output.push('\n');
    }
    output.push_str("}\n");
    write_atomic(&dependencies_file, output.as_bytes())
}

fn migrate_config_schema(project: &Path, dry_run: bool) -> Result<bool> {
    let path = project.join("shadow-plugin.properties");
    let config = PluginConfig::load(&path)?;
    if !matches!(config.schema_version, 1 | 2) {
        bail!(
            "cannot migrate unsupported shadow-plugin.properties schema {}",
            config.schema_version
        );
    }
    let parsed = parse_properties(&path)?;
    let original = fs::read_to_string(&path)?;
    let schema_line = Regex::new(r"^\s*schemaVersion\s*=")?;
    let mut lines = original
        .lines()
        .map(|line| {
            if schema_line.is_match(line) {
                "schemaVersion=2".to_owned()
            } else {
                line.to_owned()
            }
        })
        .collect::<Vec<_>>();
    for (key, value) in [
        (
            "applicationClassName",
            config.application_class_name.as_deref().unwrap_or(""),
        ),
        ("applicationTheme", config.application_theme.as_str()),
        ("activityTheme", config.activity_theme.as_str()),
        ("screenOrientation", config.screen_orientation.as_str()),
        ("softInputMode", config.soft_input_mode.as_str()),
        ("configChanges", config.config_changes.as_str()),
    ] {
        if !parsed.values.contains_key(key) {
            lines.push(format!("{key}={value}"));
        }
    }
    let migrated = lines.join("\n") + "\n";
    if migrated == original {
        return Ok(false);
    }
    if !dry_run {
        write_atomic(&path, migrated.as_bytes())?;
    }
    Ok(true)
}

fn tooling_files(template: &Path) -> Result<Vec<PathBuf>> {
    let mut files = Vec::new();
    for relative in ROOT_TOOLING {
        let path = template.join(relative);
        if !path.is_file() {
            bail!("installed template is incomplete: {}", path.display());
        }
        files.push(path);
    }
    for relative in TOOLING_DIRECTORIES {
        let directory = template.join(relative);
        if !directory.is_dir() {
            bail!("installed template is incomplete: {}", directory.display());
        }
        for entry in WalkDir::new(&directory).follow_links(false) {
            let entry = entry?;
            if entry.file_type().is_file() {
                files.push(entry.path().to_path_buf());
            }
        }
    }
    files.sort();
    files.dedup();
    Ok(files)
}

#[cfg(unix)]
fn copy_permissions(source: &Path, destination: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    let mode = fs::metadata(source)?.permissions().mode();
    fs::set_permissions(destination, fs::Permissions::from_mode(mode))?;
    Ok(())
}

#[cfg(not(unix))]
fn copy_permissions(_source: &Path, _destination: &Path) -> Result<()> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{
        ROOT_TOOLING, TOOLING_DIRECTORIES, migrate_config_schema, preserve_module_customizations,
        tooling_files,
    };
    use std::fs;

    #[test]
    fn sync_preserves_dependencies_and_migrates_schema_without_changing_identity() {
        let root = tempfile::tempdir().unwrap();
        fs::create_dir_all(root.path().join("plugin-app")).unwrap();
        fs::write(
            root.path().join("plugin-app/build.gradle"),
            "dependencies {\n    implementation 'androidx.core:core:1.13.1'\n    compileOnly files('../shadow/compile-only/shadow-runtime.jar')\n}\n",
        )
        .unwrap();
        preserve_module_customizations(root.path()).unwrap();
        assert!(
            root.path()
                .join("plugin-app/build.gradle.pre-shadow-sync")
                .is_file()
        );
        let dependencies =
            fs::read_to_string(root.path().join("plugin-app/dependencies.gradle")).unwrap();
        assert!(dependencies.contains("androidx.core:core:1.13.1"));
        assert!(!dependencies.contains("shadow-runtime.jar"));

        fs::write(
            root.path().join("shadow-plugin.properties"),
            "schemaVersion=1\npluginSlug=test\nprojectName=TestPlugin\npluginId=com.termux.shadow.test\npartKey=test\nnamespace=com.termux.shadow.test\nactivityClassName=com.termux.shadow.test.MainActivity\nresourcePackageId=0x42\npluginApkName=test.apk\nbundleBaseName=test\ndisplayName=Test\ndescription=Test\ndefaultVersionCode=1\ndefaultVersionName=1.0.0\nminHostVersionCode=1\nmaxHostVersionCode=999\n",
        )
        .unwrap();
        assert!(migrate_config_schema(root.path(), false).unwrap());
        let migrated = fs::read_to_string(root.path().join("shadow-plugin.properties")).unwrap();
        assert!(migrated.contains("schemaVersion=2"));
        assert!(migrated.contains("pluginId=com.termux.shadow.test"));
        assert!(migrated.contains("softInputMode=adjustNothing"));
    }

    #[test]
    fn sync_does_not_require_or_copy_project_gitignore() {
        let root = tempfile::tempdir().unwrap();
        for relative in ROOT_TOOLING {
            let path = root.path().join(relative);
            fs::create_dir_all(path.parent().unwrap()).unwrap();
            fs::write(path, "tooling\n").unwrap();
        }
        for relative in TOOLING_DIRECTORIES {
            let directory = root.path().join(relative);
            fs::create_dir_all(&directory).unwrap();
            fs::write(directory.join("kept"), "tooling\n").unwrap();
        }

        let files = tooling_files(root.path()).unwrap();
        assert!(!files.iter().any(|path| path.ends_with(".gitignore")));
    }
}
