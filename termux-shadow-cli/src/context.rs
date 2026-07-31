use std::env;
use std::ffi::OsString;
use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};
use serde::Serialize;

use crate::cli::DependencyPolicy;
use crate::config::PluginConfig;
use crate::workspace::Workspace;

pub const PROJECT_CONFIG: &str = "shadow-plugin.properties";
pub const DEFAULT_CONTROL_COMPONENT: &str =
    "com.termux/com.tencent.shadow.sample.host.ShadowControlReceiver";
pub const DEFAULT_CONTROL_ACTION: &str = "com.termux.shadow.CONTROL";

#[derive(Debug, Clone)]
pub struct AppContext {
    project_override: Option<PathBuf>,
    template_override: Option<PathBuf>,
    toolchain_override: Option<PathBuf>,
    pub json: bool,
    pub verbose: bool,
    pub dependency_policy: DependencyPolicy,
    pub allow_network: bool,
    pub termux_home: PathBuf,
    pub prefix: PathBuf,
    pub shadow_home: PathBuf,
    pub workspace: Option<Workspace>,
}

#[derive(Debug)]
pub struct AppContextOptions {
    pub project_override: Option<PathBuf>,
    pub template_override: Option<PathBuf>,
    pub toolchain_override: Option<PathBuf>,
    pub json: bool,
    pub verbose: bool,
    pub dependency_policy: DependencyPolicy,
    pub allow_network: bool,
    pub workspace: Option<Workspace>,
}

#[derive(Debug, Clone)]
pub struct ResolvedProject {
    pub path: PathBuf,
    pub source: &'static str,
}

#[derive(Debug, Clone)]
pub struct BuildEnvironment {
    pub portable_root: Option<PathBuf>,
    pub java_home: PathBuf,
    /// Writable Gradle user home selected for this invocation.
    pub gradle_home: PathBuf,
    /// Read-only seed cache shipped by the portable toolchain, when available.
    pub base_gradle_home: Option<PathBuf>,
    pub android_home: PathBuf,
    pub aapt2: PathBuf,
    pub gradle_distribution: Option<PathBuf>,
    pub tmp_dir: PathBuf,
    pub path: OsString,
    pub ld_library_path: Option<OsString>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Info<'a> {
    cli_version: &'a str,
    current_directory: Option<String>,
    project: Option<String>,
    project_resolution: Option<&'static str>,
    native_cache: Option<String>,
    plugin_id: Option<String>,
    template: Option<String>,
    portable_toolchain: Option<String>,
    android_home: Option<String>,
    termux_home: String,
    prefix: String,
    shadow_home: String,
    platform_status: Option<String>,
    registry_revision: Option<u64>,
    control_component: String,
    termux_pipeline: &'static str,
    gradle_worker_policy: &'static str,
    dependency_policy: &'static str,
    allow_network: bool,
    shared_gradle_cache: String,
    workspace_config: Option<String>,
    workspace_root: Option<String>,
    workspace_projects: usize,
    worker: crate::worker::WorkerInfo,
}

impl AppContext {
    pub fn new(options: AppContextOptions) -> Result<Self> {
        let termux_home = resolve_termux_home();
        let prefix = env::var_os("PREFIX")
            .map(PathBuf::from)
            .filter(|path| !path.as_os_str().is_empty())
            .unwrap_or_else(|| PathBuf::from("/data/data/com.termux/files/usr"));
        let shadow_home = env::var_os("TERMUX_SHADOW_HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|| termux_home.join(".termux-shadow"));
        Ok(Self {
            project_override: options.project_override,
            template_override: options.template_override,
            toolchain_override: options.toolchain_override,
            json: options.json,
            verbose: options.verbose,
            dependency_policy: options.dependency_policy,
            allow_network: options.allow_network,
            termux_home,
            prefix,
            shadow_home,
            workspace: options.workspace,
        })
    }

    pub fn project(&self) -> Result<PathBuf> {
        self.resolve_project().map(|resolved| resolved.path)
    }

    pub fn resolve_project(&self) -> Result<ResolvedProject> {
        if let Some(path) = &self.project_override {
            return Ok(ResolvedProject {
                path: validate_project(path)?,
                source: "explicit override (--project or SHADOW_PLUGIN_PROJECT)",
            });
        }
        if let Some(path) = env::var_os("SHADOW_PLUGIN_PROJECT") {
            return Ok(ResolvedProject {
                path: validate_project(Path::new(&path))?,
                source: "SHADOW_PLUGIN_PROJECT environment",
            });
        }
        let current = env::current_dir().context("read current directory")?;
        for ancestor in current.ancestors() {
            if ancestor.join(PROJECT_CONFIG).is_file() {
                return Ok(ResolvedProject {
                    path: canonical(ancestor)?,
                    source: "nearest ancestor of the current directory",
                });
            }
        }
        bail!(
            "PROJECT_REQUIRED: no Shadow plugin project was found in the current directory or its ancestors; cd into the intended project, use its ./shadow-plugin shim, or pass --project PATH"
        )
    }

    pub fn project_if_present(&self) -> Option<PathBuf> {
        self.project().ok()
    }

    pub fn template(&self) -> Result<PathBuf> {
        let mut candidates = Vec::new();
        if let Some(path) = &self.template_override {
            candidates.push(path.clone());
        }
        if let Some(path) = env::var_os("TERMUX_SHADOW_TEMPLATE") {
            candidates.push(PathBuf::from(path));
        }
        candidates.push(self.prefix.join("share/termux-shadow-plugin/template"));
        if let Ok(executable) = env::current_exe() {
            if let Some(prefix_bin) = executable.parent() {
                candidates.push(prefix_bin.join("../share/termux-shadow-plugin/template"));
            }
        }
        for candidate in candidates {
            if candidate.join(PROJECT_CONFIG).is_file() {
                return canonical(&candidate);
            }
        }
        bail!(
            "canonical plugin template not found; install it under {}/share/termux-shadow-plugin/template or pass --template PATH",
            self.prefix.display()
        )
    }

    pub fn build_environment(&self) -> Result<BuildEnvironment> {
        let mut portable_candidates = Vec::new();
        if let Some(path) = &self.toolchain_override {
            portable_candidates.push(path.clone());
        }
        if let Some(path) = env::var_os("TERMUX_SHADOW_ANDROID_TOOLCHAIN") {
            portable_candidates.push(PathBuf::from(path));
        }
        portable_candidates.push(self.termux_home.join("android-minimal-basic-portable"));

        for root in portable_candidates {
            if let Ok(environment) =
                BuildEnvironment::from_portable(&root, &self.shadow_home.join("gradle-cache"))
            {
                return Ok(environment);
            }
        }

        BuildEnvironment::from_host_environment(&self.shadow_home.join("gradle-cache"))
    }

    pub fn build_environment_for_project(&self, project: &Path) -> Result<BuildEnvironment> {
        let mut environment = self.build_environment()?;
        let vendor_home = project.join("vendor/gradle-home");
        if self.dependency_policy != DependencyPolicy::Online
            && crate::dependency::vendor_is_usable(project)
        {
            environment.gradle_home = vendor_home;
        }
        Ok(environment)
    }

    pub fn with_project(&self, project: PathBuf) -> Self {
        let mut cloned = self.clone();
        cloned.project_override = Some(project);
        cloned
    }

    pub fn with_dependency_policy(
        &self,
        dependency_policy: DependencyPolicy,
        allow_network: bool,
    ) -> Self {
        let mut cloned = self.clone();
        cloned.dependency_policy = dependency_policy;
        cloned.allow_network = allow_network;
        cloned
    }

    pub fn control_component(&self) -> String {
        env::var("TERMUX_SHADOW_CONTROL_COMPONENT")
            .unwrap_or_else(|_| DEFAULT_CONTROL_COMPONENT.to_owned())
    }

    pub fn control_action(&self) -> String {
        env::var("TERMUX_SHADOW_CONTROL_ACTION")
            .unwrap_or_else(|_| DEFAULT_CONTROL_ACTION.to_owned())
    }

    pub fn is_real_termux_home(&self) -> bool {
        is_termux_home(&self.termux_home)
    }

    pub fn print_info(&self) -> Result<()> {
        let resolved_project = self.resolve_project().ok();
        let project = resolved_project
            .as_ref()
            .map(|resolved| resolved.path.clone());
        let config = project
            .as_ref()
            .and_then(|path| PluginConfig::load(&path.join(PROJECT_CONFIG)).ok());
        let template = self.template().ok();
        let build_environment = self.build_environment().ok();
        let health = read_json(&self.shadow_home.join("reports/health.json"));
        let info = Info {
            cli_version: env!("CARGO_PKG_VERSION"),
            current_directory: env::current_dir().ok().map(|path| display(&path)),
            project: project.as_ref().map(|path| display(path)),
            project_resolution: resolved_project.as_ref().map(|resolved| resolved.source),
            native_cache: project
                .as_ref()
                .map(|path| display(&path.join(".gradle/shadow-native-cache.json"))),
            plugin_id: config.map(|value| value.plugin_id),
            template: template.as_ref().map(|path| display(path)),
            portable_toolchain: build_environment
                .as_ref()
                .and_then(|value| value.portable_root.as_ref())
                .map(|path| display(path)),
            android_home: build_environment
                .as_ref()
                .map(|value| display(&value.android_home)),
            termux_home: display(&self.termux_home),
            prefix: display(&self.prefix),
            shadow_home: display(&self.shadow_home),
            platform_status: health
                .as_ref()
                .and_then(|value| value.get("status"))
                .and_then(|value| value.as_str())
                .map(str::to_owned),
            registry_revision: health
                .as_ref()
                .and_then(|value| value.get("registryRevision"))
                .and_then(|value| value.as_u64()),
            control_component: self.control_component(),
            termux_pipeline: "local-only; adb is never invoked",
            gradle_worker_policy: "Android-supervised native Worker; 60-minute idle timeout",
            dependency_policy: self.dependency_policy.as_str(),
            allow_network: self.allow_network,
            shared_gradle_cache: build_environment
                .as_ref()
                .map(|environment| display(&environment.gradle_home))
                .unwrap_or_else(|| display(&self.shadow_home.join("gradle-cache"))),
            workspace_config: self
                .workspace
                .as_ref()
                .map(|workspace| display(workspace.path())),
            workspace_root: self
                .workspace
                .as_ref()
                .map(|workspace| display(workspace.root())),
            workspace_projects: self
                .workspace
                .as_ref()
                .map(|workspace| workspace.projects().len())
                .unwrap_or_default(),
            worker: crate::worker::inspect(self),
        };
        if self.json {
            println!("{}", serde_json::to_string_pretty(&info)?);
            return Ok(());
        }
        println!("shadow-plugin {}", info.cli_version);
        println!(
            "  current directory: {}",
            info.current_directory.as_deref().unwrap_or("unavailable")
        );
        println!(
            "  project: {}",
            info.project.as_deref().unwrap_or("not found")
        );
        println!(
            "  project resolution: {}",
            info.project_resolution.unwrap_or("not resolved")
        );
        println!(
            "  native cache: {}",
            info.native_cache.as_deref().unwrap_or("unavailable")
        );
        println!(
            "  pluginId: {}",
            info.plugin_id.as_deref().unwrap_or("unavailable")
        );
        println!(
            "  template: {}",
            info.template.as_deref().unwrap_or("not found")
        );
        println!(
            "  toolchain: {}",
            info.portable_toolchain
                .as_deref()
                .unwrap_or("host environment")
        );
        println!(
            "  Android SDK: {}",
            info.android_home.as_deref().unwrap_or("not found")
        );
        println!("  Termux home: {}", info.termux_home);
        println!("  Shadow home: {}", info.shadow_home);
        println!(
            "  Host: {} (revision {})",
            info.platform_status.as_deref().unwrap_or("unavailable"),
            info.registry_revision
                .map(|value| value.to_string())
                .as_deref()
                .unwrap_or("-")
        );
        println!("  control: {}", info.control_component);
        println!("  pipeline: {}", info.termux_pipeline);
        println!("  Gradle worker: {}", info.gradle_worker_policy);
        println!(
            "  dependency policy: {} (network fallback: {})",
            info.dependency_policy,
            if info.allow_network {
                "allowed"
            } else {
                "disabled"
            }
        );
        println!("  shared Gradle cache: {}", info.shared_gradle_cache);
        println!(
            "  workspace: {} ({} project(s))",
            info.workspace_config.as_deref().unwrap_or("not found"),
            info.workspace_projects
        );
        println!(
            "  Worker state: {} pid={} daemon={} requests={}",
            info.worker.status,
            info.worker
                .pid
                .map(|value| value.to_string())
                .as_deref()
                .unwrap_or("-"),
            info.worker.gradle_daemon,
            info.worker.requests_served
        );
        Ok(())
    }
}

impl BuildEnvironment {
    fn from_portable(root: &Path, shared_gradle_home: &Path) -> Result<Self> {
        let root = canonical(root)?;
        let java_home = root.join("toolchain/usr/lib/jvm/java-17-openjdk");
        let base_gradle_home = root.join("gradle-home");
        let android_home = root.join("project/android-sdk");
        let aapt2 = android_home.join("build-tools/35.0.0/aapt2");
        require_file(&java_home.join("bin/java"), "portable Java")?;
        require_dir(&base_gradle_home, "portable Gradle home")?;
        require_file(&aapt2, "portable aapt2")?;
        require_file(
            &android_home.join("platforms/android-35/android.jar"),
            "Android 35 platform",
        )?;
        let tmp_dir = root.join("runtime/tmp");
        fs::create_dir_all(&tmp_dir)
            .with_context(|| format!("create portable temp directory {}", tmp_dir.display()))?;
        let path = env::join_paths([
            java_home.join("bin"),
            android_home.join("build-tools/35.0.0"),
            root.join("toolchain/usr/bin"),
            PathBuf::from("/system/bin"),
            PathBuf::from("/system/xbin"),
        ])?;
        let ld_library_path = Some(env::join_paths([
            root.join("toolchain/usr/lib"),
            java_home.join("lib"),
            java_home.join("lib/server"),
        ])?);
        let gradle_distribution = Some(find_gradle_distribution(&base_gradle_home)?);
        Ok(Self {
            portable_root: Some(root),
            java_home,
            gradle_home: shared_gradle_home.to_path_buf(),
            base_gradle_home: Some(base_gradle_home),
            android_home,
            aapt2,
            gradle_distribution,
            tmp_dir,
            path,
            ld_library_path,
        })
    }

    fn from_host_environment(shared_gradle_home: &Path) -> Result<Self> {
        let android_home = env::var_os("ANDROID_HOME")
            .or_else(|| env::var_os("ANDROID_SDK_ROOT"))
            .map(PathBuf::from)
            .context(
                "portable Android toolchain not found and ANDROID_HOME/ANDROID_SDK_ROOT is unset",
            )?;
        let java_home = env::var_os("JAVA_HOME")
            .map(PathBuf::from)
            .or_else(detect_java_home)
            .context("JAVA_HOME is unset and Java could not be discovered")?;
        let configured_gradle_home = env::var_os("GRADLE_USER_HOME").map(PathBuf::from);
        let base_gradle_home = configured_gradle_home
            .clone()
            .unwrap_or_else(|| resolve_termux_home().join(".gradle"));
        let gradle_home =
            configured_gradle_home.unwrap_or_else(|| shared_gradle_home.to_path_buf());
        let base_gradle_home = (base_gradle_home != gradle_home).then_some(base_gradle_home);
        let aapt2 = find_aapt2(&android_home)?;
        let tmp_dir = env::temp_dir();
        let mut paths = vec![java_home.join("bin")];
        if let Some(current) = env::var_os("PATH") {
            paths.extend(env::split_paths(&current));
        }
        Ok(Self {
            portable_root: None,
            java_home,
            gradle_home,
            base_gradle_home,
            android_home,
            aapt2,
            gradle_distribution: None,
            tmp_dir,
            path: env::join_paths(paths)?,
            ld_library_path: env::var_os("LD_LIBRARY_PATH"),
        })
    }

    pub fn apply(&self, command: &mut std::process::Command, context: &AppContext) {
        command
            .env("JAVA_HOME", &self.java_home)
            .env("GRADLE_USER_HOME", &self.gradle_home)
            .env("ANDROID_HOME", &self.android_home)
            .env("ANDROID_SDK_ROOT", &self.android_home)
            .env("TMPDIR", &self.tmp_dir)
            .env("PATH", &self.path)
            .env("TERMUX_HOME", &context.termux_home)
            .env("PREFIX", &context.prefix)
            .env(
                "TERMUX_SHADOW_DEPENDENCY_POLICY",
                context.dependency_policy.as_str(),
            )
            .env(
                "TERMUX_SHADOW_ALLOW_NETWORK",
                if context.allow_network { "1" } else { "0" },
            );
        if let Some(value) = &self.ld_library_path {
            command.env("LD_LIBRARY_PATH", value);
        }
    }
}

pub fn resolve_termux_home() -> PathBuf {
    if let Some(path) = env::var_os("TERMUX_HOME") {
        let path = PathBuf::from(path);
        if !path.as_os_str().is_empty() {
            return path;
        }
    }
    if let Some(path) = env::var_os("HOME") {
        let path = PathBuf::from(path);
        if path.join(".termux-shadow").exists()
            || path.to_string_lossy().contains("com.termux/files/home")
        {
            return path;
        }
    }
    PathBuf::from("/data/data/com.termux/files/home")
}

pub fn is_termux_home(path: &Path) -> bool {
    let normalized = normalize_termux_path_identity(path);
    let text = normalized.to_string_lossy();
    let Some(relative) = text.strip_prefix("/data/user/") else {
        return false;
    };
    let Some((user, suffix)) = relative.split_once('/') else {
        return false;
    };
    !user.is_empty()
        && user.chars().all(|character| character.is_ascii_digit())
        && suffix == "com.termux/files/home"
}

/// Returns a stable logical identity for Android's two aliases of the Termux data tree.
/// The returned path is for comparisons and fingerprints; callers should keep using the
/// original/canonical filesystem path for I/O.
pub fn normalize_termux_path_identity(path: &Path) -> PathBuf {
    const LEGACY: &str = "/data/data/com.termux/files";
    const USER_ZERO: &str = "/data/user/0/com.termux/files";
    let text = path.to_string_lossy();
    if let Some(relative) = text
        .strip_prefix(LEGACY)
        .filter(|relative| relative.is_empty() || relative.starts_with('/'))
    {
        return PathBuf::from(format!("{USER_ZERO}{relative}"));
    }
    if let Some(relative) = text
        .strip_prefix(USER_ZERO)
        .filter(|relative| relative.is_empty() || relative.starts_with('/'))
    {
        return PathBuf::from(format!("{USER_ZERO}{relative}"));
    }
    path.to_path_buf()
}

pub fn same_logical_path(left: &Path, right: &Path) -> bool {
    let left = fs::canonicalize(left).unwrap_or_else(|_| left.to_path_buf());
    let right = fs::canonicalize(right).unwrap_or_else(|_| right.to_path_buf());
    normalize_termux_path_identity(&left) == normalize_termux_path_identity(&right)
}

fn validate_project(path: &Path) -> Result<PathBuf> {
    if !path.join(PROJECT_CONFIG).is_file() {
        bail!(
            "{} is not a Shadow plugin project (missing {})",
            path.display(),
            PROJECT_CONFIG
        );
    }
    canonical(path)
}

fn canonical(path: &Path) -> Result<PathBuf> {
    fs::canonicalize(path).with_context(|| format!("resolve {}", path.display()))
}

fn require_file(path: &Path, label: &str) -> Result<()> {
    if path.is_file() {
        Ok(())
    } else {
        bail!("{label} not found: {}", path.display())
    }
}

fn require_dir(path: &Path, label: &str) -> Result<()> {
    if path.is_dir() {
        Ok(())
    } else {
        bail!("{label} not found: {}", path.display())
    }
}

fn find_aapt2(android_home: &Path) -> Result<PathBuf> {
    let preferred = android_home.join("build-tools/35.0.0/aapt2");
    if preferred.is_file() {
        return Ok(preferred);
    }
    let build_tools = android_home.join("build-tools");
    let mut candidates = fs::read_dir(&build_tools)
        .with_context(|| format!("read {}", build_tools.display()))?
        .filter_map(Result::ok)
        .map(|entry| entry.path().join("aapt2"))
        .filter(|path| path.is_file())
        .collect::<Vec<_>>();
    candidates.sort();
    candidates
        .pop()
        .with_context(|| format!("aapt2 not found under {}", build_tools.display()))
}

fn find_gradle_distribution(gradle_home: &Path) -> Result<PathBuf> {
    let root = gradle_home.join("wrapper/dists/gradle-9.5.0-bin");
    for entry in fs::read_dir(&root).with_context(|| format!("read {}", root.display()))? {
        let candidate = entry?.path().join("gradle-9.5.0");
        if candidate
            .join("lib/gradle-gradle-cli-main-9.5.0.jar")
            .is_file()
            && candidate
                .join("lib/agents/gradle-instrumentation-agent-9.5.0.jar")
                .is_file()
        {
            return Ok(candidate);
        }
    }
    bail!(
        "bundled Gradle 9.5.0 distribution not found under {}",
        root.display()
    )
}

fn detect_java_home() -> Option<PathBuf> {
    [
        "/usr/lib/jvm/java-17-openjdk-amd64",
        "/usr/lib/jvm/java-17-openjdk",
        "/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk",
    ]
    .into_iter()
    .map(PathBuf::from)
    .find(|path| path.join("bin/java").is_file())
}

fn read_json(path: &Path) -> Option<serde_json::Value> {
    fs::read(path)
        .ok()
        .and_then(|bytes| serde_json::from_slice(&bytes).ok())
}

fn display(path: &Path) -> String {
    path.display().to_string()
}

#[cfg(test)]
mod tests {
    use super::{
        AppContext, AppContextOptions, is_termux_home, normalize_termux_path_identity,
        same_logical_path,
    };
    use crate::cli::DependencyPolicy;
    use std::fs;
    use std::path::Path;

    #[test]
    fn recognizes_both_termux_home_aliases() {
        assert!(is_termux_home(Path::new(
            "/data/data/com.termux/files/home"
        )));
        assert!(is_termux_home(Path::new(
            "/data/user/0/com.termux/files/home"
        )));
        assert!(is_termux_home(Path::new(
            "/data/user/10/com.termux/files/home"
        )));
        assert!(!is_termux_home(Path::new("/root")));
    }

    #[test]
    fn normalizes_android_termux_path_aliases_for_identity() {
        assert_eq!(
            normalize_termux_path_identity(Path::new(
                "/data/data/com.termux/files/home/termux-shadow-notes"
            )),
            Path::new("/data/user/0/com.termux/files/home/termux-shadow-notes")
        );
        assert!(same_logical_path(
            Path::new("/data/data/com.termux/files/home/termux-shadow-notes"),
            Path::new("/data/user/0/com.termux/files/home/termux-shadow-notes")
        ));
    }

    #[test]
    fn reports_an_explicit_project_resolution() {
        let temp = tempfile::tempdir().unwrap();
        fs::write(
            temp.path().join("shadow-plugin.properties"),
            "schemaVersion=1\n",
        )
        .unwrap();
        let context = AppContext::new(AppContextOptions {
            project_override: Some(temp.path().into()),
            template_override: None,
            toolchain_override: None,
            json: false,
            verbose: false,
            dependency_policy: DependencyPolicy::CacheFirst,
            allow_network: false,
            workspace: None,
        })
        .unwrap();
        let resolved = context.resolve_project().unwrap();
        assert_eq!(resolved.path, temp.path().canonicalize().unwrap());
        assert!(resolved.source.starts_with("explicit override"));
    }
}
