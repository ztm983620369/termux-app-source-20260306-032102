use std::collections::{BTreeMap, BTreeSet};
use std::env;
use std::ffi::OsString;
use std::fs;
use std::path::{Component, Path, PathBuf};
use std::process::Command as ProcessCommand;

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};

use crate::cli::{Command, DependencyPolicy, DepsCommand};
use crate::context::{AppContext, PROJECT_CONFIG};

pub const WORKSPACE_FILE: &str = ".shadow-workspace.toml";
const WORKSPACE_SCHEMA_VERSION: u32 = 1;

#[derive(Debug, Clone)]
pub struct Workspace {
    path: PathBuf,
    root: PathBuf,
    defaults: WorkspaceDefaults,
    projects: BTreeMap<String, PathBuf>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "kebab-case", deny_unknown_fields)]
pub struct WorkspaceDefaults {
    pub dependency_policy: Option<DependencyPolicy>,
    pub toolchain: Option<PathBuf>,
    pub template: Option<PathBuf>,
    pub output: Option<WorkspaceOutput>,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "kebab-case")]
pub enum WorkspaceOutput {
    Human,
    Json,
    Agent,
}

#[derive(Debug, Clone, Copy)]
pub enum BatchKind {
    Dev,
    Doctor,
    DepsAudit,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct BatchOutput {
    ok: bool,
    action: &'static str,
    status: &'static str,
    workspace_config: String,
    execution_policy: &'static str,
    projects: Vec<BatchProjectOutput>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct BatchProjectOutput {
    name: String,
    path: String,
    ok: bool,
    exit_code: i32,
    #[serde(skip_serializing_if = "Option::is_none")]
    result: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "kebab-case", deny_unknown_fields)]
struct WorkspaceDocument {
    #[serde(default = "default_schema_version")]
    schema_version: u32,
    #[serde(default)]
    defaults: WorkspaceDefaults,
    #[serde(default)]
    projects: BTreeMap<String, PathBuf>,
}

impl Workspace {
    pub fn discover(explicit: Option<&Path>, project_hint: Option<&Path>) -> Result<Option<Self>> {
        let selected = if let Some(explicit) = explicit {
            Some(absolute_from_current(explicit)?)
        } else {
            let current = env::current_dir().context("read current directory")?;
            find_nearest(&current).or_else(|| {
                project_hint
                    .filter(|hint| !is_workspace_alias(hint))
                    .and_then(find_nearest)
            })
        };
        selected.map(|path| Self::load(&path)).transpose()
    }

    fn load(path: &Path) -> Result<Self> {
        let path = fs::canonicalize(path)
            .with_context(|| format!("resolve workspace configuration {}", path.display()))?;
        if path.file_name().and_then(|name| name.to_str()) != Some(WORKSPACE_FILE) {
            bail!(
                "workspace configuration must be named {WORKSPACE_FILE}: {}",
                path.display()
            );
        }
        let root = path
            .parent()
            .context("workspace configuration has no parent")?
            .to_path_buf();
        let text = fs::read_to_string(&path)
            .with_context(|| format!("read workspace configuration {}", path.display()))?;
        let document: WorkspaceDocument = toml::from_str(&text)
            .with_context(|| format!("parse workspace configuration {}", path.display()))?;
        if document.schema_version != WORKSPACE_SCHEMA_VERSION {
            bail!(
                "unsupported workspace schema {} in {}; expected {}",
                document.schema_version,
                path.display(),
                WORKSPACE_SCHEMA_VERSION
            );
        }

        let defaults = WorkspaceDefaults {
            dependency_policy: document.defaults.dependency_policy,
            toolchain: document
                .defaults
                .toolchain
                .map(|value| resolve_default_path(&root, &value))
                .transpose()?,
            template: document
                .defaults
                .template
                .map(|value| resolve_default_path(&root, &value))
                .transpose()?,
            output: document.defaults.output,
        };
        let mut projects = BTreeMap::new();
        let mut identities = BTreeSet::new();
        for (name, relative) in document.projects {
            validate_project_name(&name)?;
            if relative.is_absolute()
                || relative
                    .components()
                    .any(|component| matches!(component, Component::ParentDir))
            {
                bail!(
                    "workspace project {name} must be a relative path contained by {}",
                    root.display()
                );
            }
            let project = fs::canonicalize(root.join(&relative)).with_context(|| {
                format!(
                    "resolve workspace project {name} from {}",
                    relative.display()
                )
            })?;
            if !project.starts_with(&root) {
                bail!(
                    "workspace project {name} escapes workspace root {}",
                    root.display()
                );
            }
            if !project.join(PROJECT_CONFIG).is_file() {
                bail!(
                    "workspace project {name} is missing {}: {}",
                    PROJECT_CONFIG,
                    project.display()
                );
            }
            if !identities.insert(project.clone()) {
                bail!(
                    "workspace project {name} duplicates another project path: {}",
                    project.display()
                );
            }
            projects.insert(name, project);
        }
        Ok(Self {
            path,
            root,
            defaults,
            projects,
        })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn defaults(&self) -> &WorkspaceDefaults {
        &self.defaults
    }

    pub fn projects(&self) -> &BTreeMap<String, PathBuf> {
        &self.projects
    }

    pub fn resolve_project_argument(&self, requested: PathBuf) -> Result<PathBuf> {
        let Some(text) = requested.to_str() else {
            return Ok(requested);
        };
        let Some(name) = text.strip_prefix('@') else {
            return Ok(requested);
        };
        if name.is_empty() {
            bail!("workspace project alias cannot be empty; use --project @NAME");
        }
        self.projects.get(name).cloned().with_context(|| {
            let available = self.projects.keys().cloned().collect::<Vec<_>>().join(", ");
            format!(
                "workspace project @{name} is not declared in {}; available: {}",
                self.path.display(),
                if available.is_empty() {
                    "(none)"
                } else {
                    &available
                }
            )
        })
    }
}

pub fn batch_kind(command: &Command) -> Option<BatchKind> {
    match command {
        Command::Dev(args) if args.workspace => Some(BatchKind::Dev),
        Command::Doctor(args) if args.workspace => Some(BatchKind::Doctor),
        Command::Deps(args) if args.workspace && matches!(&args.command, DepsCommand::Audit) => {
            Some(BatchKind::DepsAudit)
        }
        _ => None,
    }
}

pub fn run_batch(context: &AppContext, kind: BatchKind, arguments: &[OsString]) -> Result<i32> {
    let workspace = context.workspace.as_ref().context(
        "WORKSPACE_REQUIRED: batch commands require .shadow-workspace.toml; pass --workspace-config PATH or run inside a configured workspace",
    )?;
    if workspace.projects.is_empty() {
        bail!(
            "WORKSPACE_EMPTY: {} declares no projects",
            workspace.path.display()
        );
    }
    let child_arguments = child_arguments(arguments);
    let executable = env::current_exe().context("resolve current shadow-plugin executable")?;
    let mut projects = Vec::with_capacity(workspace.projects.len());
    for (name, project) in &workspace.projects {
        if !context.json {
            println!("\n==> {name} ({})", project.display());
        }
        let output = ProcessCommand::new(&executable)
            .arg("--workspace-config")
            .arg(&workspace.path)
            .arg("--project")
            .arg(project)
            .args(&child_arguments)
            .current_dir(project)
            .env_remove("TERMUX_SHADOW_REQUEST_ID")
            .output()
            .with_context(|| format!("start workspace command for {name}"))?;
        let exit_code = output.status.code().unwrap_or(1);
        if context.json {
            let result = serde_json::from_slice::<serde_json::Value>(&output.stdout).ok();
            let ok = output.status.success() && result.is_some();
            let error = if result.is_none() {
                let stdout = bounded_text(&output.stdout);
                let stderr = bounded_text(&output.stderr);
                Some(format!(
                    "child did not emit one JSON document; stdout={stdout:?} stderr={stderr:?}"
                ))
            } else if !output.stderr.is_empty() {
                Some(bounded_text(&output.stderr))
            } else {
                None
            };
            projects.push(BatchProjectOutput {
                name: name.clone(),
                path: project.display().to_string(),
                ok,
                exit_code,
                result,
                error,
            });
        } else {
            let ok = output.status.success();
            print!("{}", String::from_utf8_lossy(&output.stdout));
            eprint!("{}", String::from_utf8_lossy(&output.stderr));
            projects.push(BatchProjectOutput {
                name: name.clone(),
                path: project.display().to_string(),
                ok,
                exit_code,
                result: None,
                error: None,
            });
        }
    }
    let ok = projects.iter().all(|project| project.ok);
    let output = BatchOutput {
        ok,
        action: match kind {
            BatchKind::Dev => "dev.workspace",
            BatchKind::Doctor => "doctor.workspace",
            BatchKind::DepsAudit => "deps.audit.workspace",
        },
        status: if ok { "PASS" } else { "FAILED" },
        workspace_config: workspace.path.display().to_string(),
        execution_policy: "SERIALIZED_WORKER",
        projects,
    };
    if context.json {
        if context.verbose {
            println!("{}", serde_json::to_string_pretty(&output)?);
        } else {
            println!("{}", serde_json::to_string(&output)?);
        }
    } else {
        println!(
            "\nworkspace: {} — {}",
            output.status, output.workspace_config
        );
    }
    Ok(if ok { 0 } else { 1 })
}

fn child_arguments(arguments: &[OsString]) -> Vec<OsString> {
    let mut cleaned = Vec::new();
    let mut index = 1;
    while index < arguments.len() {
        let text = arguments[index].to_string_lossy();
        if text == "--workspace" {
            index += 1;
            continue;
        }
        if matches!(
            text.as_ref(),
            "--project" | "--request-id" | "--workspace-config"
        ) {
            index += 2;
            continue;
        }
        if text.starts_with("--project=")
            || text.starts_with("--request-id=")
            || text.starts_with("--workspace-config=")
        {
            index += 1;
            continue;
        }
        cleaned.push(arguments[index].clone());
        index += 1;
    }
    cleaned
}

fn bounded_text(bytes: &[u8]) -> String {
    const LIMIT: usize = 4_096;
    let start = bytes.len().saturating_sub(LIMIT);
    String::from_utf8_lossy(&bytes[start..]).trim().to_owned()
}

fn find_nearest(start: &Path) -> Option<PathBuf> {
    let start = if start.is_file() {
        start.parent()?
    } else {
        start
    };
    start
        .ancestors()
        .map(|ancestor| ancestor.join(WORKSPACE_FILE))
        .find(|candidate| candidate.is_file())
}

fn absolute_from_current(path: &Path) -> Result<PathBuf> {
    if path.is_absolute() {
        Ok(path.to_path_buf())
    } else {
        Ok(env::current_dir()
            .context("read current directory")?
            .join(path))
    }
}

fn resolve_default_path(root: &Path, value: &Path) -> Result<PathBuf> {
    if value
        .components()
        .any(|component| matches!(component, Component::ParentDir))
    {
        bail!(
            "workspace default path may not contain '..': {}",
            value.display()
        );
    }
    Ok(if value.is_absolute() {
        value.to_path_buf()
    } else {
        root.join(value)
    })
}

fn validate_project_name(name: &str) -> Result<()> {
    let mut characters = name.chars();
    let valid_first = characters
        .next()
        .is_some_and(|value| value.is_ascii_alphabetic());
    let valid_rest =
        characters.all(|value| value.is_ascii_alphanumeric() || matches!(value, '_' | '-'));
    if !valid_first || !valid_rest || name.len() > 64 {
        bail!(
            "invalid workspace project name {name:?}; use 1-64 ASCII letters, digits, '_' or '-', starting with a letter"
        );
    }
    Ok(())
}

fn is_workspace_alias(path: &Path) -> bool {
    path.to_str().is_some_and(|value| value.starts_with('@'))
}

const fn default_schema_version() -> u32 {
    WORKSPACE_SCHEMA_VERSION
}

#[cfg(test)]
mod tests {
    use super::{Workspace, WorkspaceOutput, child_arguments};
    use crate::cli::DependencyPolicy;
    use std::ffi::OsString;
    use std::fs;

    fn fixture() -> tempfile::TempDir {
        let temp = tempfile::tempdir().unwrap();
        let notes = temp.path().join("plugins/notes");
        fs::create_dir_all(&notes).unwrap();
        fs::write(notes.join("shadow-plugin.properties"), "schemaVersion=2\n").unwrap();
        fs::write(
            temp.path().join(".shadow-workspace.toml"),
            r#"
schema-version = 1

[defaults]
dependency-policy = "offline"
output = "agent"
toolchain = "toolchain"

[projects]
notes = "plugins/notes"
"#,
        )
        .unwrap();
        temp
    }

    #[test]
    fn loads_defaults_and_resolves_explicit_aliases() {
        let temp = fixture();
        let workspace = Workspace::load(&temp.path().join(".shadow-workspace.toml")).unwrap();
        assert_eq!(
            workspace.defaults().dependency_policy,
            Some(DependencyPolicy::Offline)
        );
        assert_eq!(workspace.defaults().output, Some(WorkspaceOutput::Agent));
        assert_eq!(
            workspace.resolve_project_argument("@notes".into()).unwrap(),
            temp.path().join("plugins/notes").canonicalize().unwrap()
        );
        let expected_toolchain = temp.path().join("toolchain");
        assert_eq!(
            workspace.defaults().toolchain.as_deref(),
            Some(expected_toolchain.as_path())
        );
    }

    #[test]
    fn rejects_unknown_keys_and_project_escapes() {
        let temp = fixture();
        fs::write(
            temp.path().join(".shadow-workspace.toml"),
            "[defaults]\ndependency-polciy = \"offline\"\n",
        )
        .unwrap();
        assert!(Workspace::load(&temp.path().join(".shadow-workspace.toml")).is_err());

        fs::write(
            temp.path().join(".shadow-workspace.toml"),
            "[projects]\nnotes = \"../notes\"\n",
        )
        .unwrap();
        assert!(Workspace::load(&temp.path().join(".shadow-workspace.toml")).is_err());
    }

    #[test]
    fn batch_child_arguments_remove_scope_and_idempotency_overrides() {
        let arguments = [
            "shadow-plugin",
            "--project",
            "@notes",
            "dev",
            "--workspace",
            "--request-id=req-fixed",
            "--no-run",
        ]
        .map(OsString::from);
        assert_eq!(
            child_arguments(&arguments),
            ["dev", "--no-run"].map(OsString::from)
        );
    }
}
