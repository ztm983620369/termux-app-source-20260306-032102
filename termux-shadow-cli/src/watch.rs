use std::collections::{BTreeMap, BTreeSet};
use std::env;
use std::ffi::OsString;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command as ProcessCommand;
use std::sync::mpsc::{self, RecvTimeoutError};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};
use notify::{Config, RecommendedWatcher, RecursiveMode, Watcher};
use walkdir::WalkDir;

use crate::cache;
use crate::context::AppContext;
use crate::fsutil::sha256_file;

#[derive(Debug, Clone, PartialEq, Eq)]
struct SourceSnapshot {
    files: BTreeMap<PathBuf, String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ChangeKind {
    Added,
    Modified,
    Removed,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct SourceChange {
    kind: ChangeKind,
    path: PathBuf,
}

pub fn run(
    context: &AppContext,
    arguments: &[OsString],
    show_diff: bool,
    debounce_ms: Option<u64>,
) -> Result<i32> {
    if context.json {
        bail!(
            "WATCH_OUTPUT_UNSUPPORTED: dev --watch is a long-lived human event stream and cannot use --json/--agent; use ordinary `shadow-plugin dev --agent` for one-document automation"
        );
    }
    let project = context.project()?;
    let executable = env::current_exe().context("resolve current shadow-plugin executable")?;
    let child_arguments = child_arguments(arguments);
    println!(
        "watch: initial resumable deployment for {}",
        project.display()
    );
    let initial = run_dev_child(context, &executable, &project, &child_arguments)?;
    if !initial.success() {
        eprintln!(
            "watch: initial deployment failed (exit {}); watching continues so the next source edit can recover",
            initial.code().unwrap_or(1)
        );
    }

    let mut baseline = SourceSnapshot::capture(&project)?;
    let debounce = Duration::from_millis(debounce_ms.unwrap_or(500).clamp(100, 10_000));
    let (sender, receiver) = mpsc::channel();
    let mut watcher = RecommendedWatcher::new(
        move |event| {
            let _ = sender.send(event);
        },
        Config::default(),
    )
    .context("create filesystem watcher")?;
    watcher
        .watch(&project, RecursiveMode::Recursive)
        .with_context(|| format!("watch project {}", project.display()))?;
    println!(
        "watch: ready (debounce={}ms, Ctrl-C to stop)",
        debounce.as_millis()
    );

    loop {
        let first = receiver.recv().context("filesystem watcher stopped")?;
        if let Err(error) = first {
            eprintln!("watch: filesystem event error: {error}");
        }
        let mut deadline = Instant::now() + debounce;
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            match receiver.recv_timeout(remaining) {
                Ok(Ok(_)) => deadline = Instant::now() + debounce,
                Ok(Err(error)) => {
                    eprintln!("watch: filesystem event error: {error}");
                    deadline = Instant::now() + debounce;
                }
                Err(RecvTimeoutError::Timeout) => break,
                Err(RecvTimeoutError::Disconnected) => {
                    bail!("filesystem watcher disconnected")
                }
            }
        }

        let current = match SourceSnapshot::capture(&project) {
            Ok(snapshot) => snapshot,
            Err(error) => {
                eprintln!("watch: cannot snapshot source yet: {error:#}");
                continue;
            }
        };
        let changes = baseline.diff(&current);
        if changes.is_empty() {
            continue;
        }
        baseline = current;
        if show_diff {
            print_changes(&changes);
        } else {
            println!("watch: {} build input(s) changed", changes.len());
        }
        let status = run_dev_child(context, &executable, &project, &child_arguments)?;
        if status.success() {
            println!("watch: deployment complete; waiting for changes");
        } else {
            eprintln!(
                "watch: deployment failed (exit {}); waiting for the next source edit",
                status.code().unwrap_or(1)
            );
        }
    }
}

fn run_dev_child(
    context: &AppContext,
    executable: &Path,
    project: &Path,
    child_arguments: &[OsString],
) -> Result<std::process::ExitStatus> {
    let mut command = ProcessCommand::new(executable);
    if let Some(workspace) = &context.workspace {
        command.arg("--workspace-config").arg(workspace.path());
    }
    command
        .arg("--human")
        .arg("--project")
        .arg(project)
        .args(child_arguments)
        .current_dir(project)
        .env_remove("TERMUX_SHADOW_REQUEST_ID")
        .status()
        .context("start watched dev request")
}

fn child_arguments(arguments: &[OsString]) -> Vec<OsString> {
    let flags_without_values = ["--watch", "--diff", "--workspace", "--human"];
    let flags_with_values = [
        "--debounce-ms",
        "--project",
        "--request-id",
        "--workspace-config",
    ];
    let prefixes = [
        "--debounce-ms=",
        "--project=",
        "--request-id=",
        "--workspace-config=",
    ];
    let mut cleaned = Vec::new();
    let mut index = 1;
    while index < arguments.len() {
        let text = arguments[index].to_string_lossy();
        if flags_without_values.contains(&text.as_ref()) {
            index += 1;
            continue;
        }
        if flags_with_values.contains(&text.as_ref()) {
            index += 2;
            continue;
        }
        if prefixes.iter().any(|prefix| text.starts_with(prefix)) {
            index += 1;
            continue;
        }
        cleaned.push(arguments[index].clone());
        index += 1;
    }
    cleaned
}

impl SourceSnapshot {
    fn capture(project: &Path) -> Result<Self> {
        let mut files = BTreeMap::new();
        for entry in WalkDir::new(project)
            .follow_links(false)
            .into_iter()
            .filter_entry(|entry| !cache::generated_entry(entry, project))
        {
            let entry = entry?;
            if !entry.file_type().is_file() && !entry.file_type().is_symlink() {
                continue;
            }
            let relative = entry.path().strip_prefix(project)?.to_path_buf();
            if !cache::is_project_build_input(&relative) {
                continue;
            }
            let fingerprint = if entry.file_type().is_symlink() {
                format!("symlink:{}", fs::read_link(entry.path())?.display())
            } else {
                sha256_file(entry.path())?
            };
            files.insert(relative, fingerprint);
        }
        Ok(Self { files })
    }

    fn diff(&self, current: &Self) -> Vec<SourceChange> {
        let paths = self
            .files
            .keys()
            .chain(current.files.keys())
            .cloned()
            .collect::<BTreeSet<_>>();
        paths
            .into_iter()
            .filter_map(|path| {
                let before = self.files.get(&path);
                let after = current.files.get(&path);
                let kind = match (before, after) {
                    (None, Some(_)) => ChangeKind::Added,
                    (Some(_), None) => ChangeKind::Removed,
                    (Some(before), Some(after)) if before != after => ChangeKind::Modified,
                    _ => return None,
                };
                Some(SourceChange { kind, path })
            })
            .collect()
    }
}

fn print_changes(changes: &[SourceChange]) {
    const DISPLAY_LIMIT: usize = 40;
    println!("watch: source change summary");
    for change in changes.iter().take(DISPLAY_LIMIT) {
        let marker = match change.kind {
            ChangeKind::Added => "A",
            ChangeKind::Modified => "M",
            ChangeKind::Removed => "D",
        };
        println!("  {marker} {}", change.path.display());
    }
    if changes.len() > DISPLAY_LIMIT {
        println!("  … and {} more", changes.len() - DISPLAY_LIMIT);
    }
}

#[cfg(test)]
mod tests {
    use super::{ChangeKind, SourceSnapshot, child_arguments};
    use std::ffi::OsString;
    use std::fs;

    #[test]
    fn snapshots_report_added_modified_and_removed_build_inputs_only() {
        let temp = tempfile::tempdir().unwrap();
        let source = temp.path().join("plugin-app/src/main/java");
        fs::create_dir_all(&source).unwrap();
        fs::write(source.join("Old.java"), "old").unwrap();
        fs::write(temp.path().join("README.md"), "ignored").unwrap();
        let before = SourceSnapshot::capture(temp.path()).unwrap();

        fs::write(source.join("Old.java"), "changed").unwrap();
        fs::write(source.join("New.java"), "new").unwrap();
        fs::write(temp.path().join("README.md"), "still ignored").unwrap();
        let middle = SourceSnapshot::capture(temp.path()).unwrap();
        let changes = before.diff(&middle);
        assert_eq!(changes.len(), 2);
        assert!(
            changes
                .iter()
                .any(|change| change.kind == ChangeKind::Added)
        );
        assert!(
            changes
                .iter()
                .any(|change| change.kind == ChangeKind::Modified)
        );

        fs::remove_file(source.join("Old.java")).unwrap();
        let after = SourceSnapshot::capture(temp.path()).unwrap();
        assert!(
            middle
                .diff(&after)
                .iter()
                .any(|change| change.kind == ChangeKind::Removed)
        );
    }

    #[test]
    fn child_arguments_remove_watcher_and_replay_unsafe_flags() {
        let arguments = [
            "shadow-plugin",
            "dev",
            "--watch",
            "--diff",
            "--debounce-ms=750",
            "--request-id",
            "fixed-request",
            "--no-run",
        ]
        .map(OsString::from);
        assert_eq!(
            child_arguments(&arguments),
            ["dev", "--no-run"].map(OsString::from)
        );
    }
}
