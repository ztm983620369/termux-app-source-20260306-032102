use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};
use serde::Serialize;
use walkdir::WalkDir;

use crate::cli::{DoctorArgs, SyncArgs};
use crate::context::AppContext;
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
    ".gitignore",
];
const TOOLING_DIRECTORIES: [&str; 3] = ["gradle", "scripts", "shadow"];

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct SyncOutput {
    project: String,
    template: String,
    dry_run: bool,
    changed: Vec<String>,
    unchanged: usize,
    preserved: [&'static str; 2],
}

pub fn run(context: &AppContext, args: SyncArgs) -> Result<()> {
    let project = context.project()?;
    let template = context.template()?;
    if fs::canonicalize(&project)? == fs::canonicalize(&template)? {
        bail!("current project is the installed template; no sync is required");
    }
    let files = tooling_files(&template)?;
    let mut changed = Vec::new();
    let mut unchanged = 0usize;
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
        write_atomic(&destination, &source_bytes)
            .with_context(|| format!("sync {}", relative.display()))?;
        copy_permissions(&source, &destination)?;
    }

    let output = SyncOutput {
        project: project.display().to_string(),
        template: template.display().to_string(),
        dry_run: args.dry_run,
        changed,
        unchanged,
        preserved: ["shadow-plugin.properties", "plugin-app/"],
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
        println!("  preserved: shadow-plugin.properties, plugin-app/");
    }

    if !args.dry_run && !context.json {
        doctor::run_for_project(
            context,
            &project,
            DoctorArgs {
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
