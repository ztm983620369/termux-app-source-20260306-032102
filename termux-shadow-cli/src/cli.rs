use std::path::PathBuf;

use clap::{Args, Parser, Subcommand, ValueEnum};
use serde::{Deserialize, Serialize};

#[derive(Debug, Parser)]
#[command(
    name = "shadow-plugin",
    version,
    about = "Fast native workflow for Termux Shadow plugins",
    long_about = "Create, diagnose, build, publish, query, launch, upgrade, and recover managed Termux Shadow plugins from one native command."
)]
pub struct Cli {
    /// Plugin project path or @NAME from .shadow-workspace.toml.
    /// Defaults to the nearest parent containing shadow-plugin.properties.
    #[arg(
        long,
        global = true,
        env = "SHADOW_PLUGIN_PROJECT",
        value_name = "PATH",
        help_heading = "Global options"
    )]
    pub project: Option<PathBuf>,

    /// Workspace configuration. Defaults to the nearest .shadow-workspace.toml.
    #[arg(
        long,
        global = true,
        env = "TERMUX_SHADOW_WORKSPACE",
        value_name = "PATH",
        help_heading = "Global options"
    )]
    pub workspace_config: Option<PathBuf>,

    /// Canonical scaffold template.
    #[arg(
        long,
        global = true,
        env = "TERMUX_SHADOW_TEMPLATE",
        value_name = "PATH",
        help_heading = "Global options"
    )]
    pub template: Option<PathBuf>,

    /// Portable Android toolchain root.
    #[arg(
        long,
        global = true,
        env = "TERMUX_SHADOW_ANDROID_TOOLCHAIN",
        value_name = "PATH",
        help_heading = "Global options"
    )]
    pub toolchain: Option<PathBuf>,

    /// Dependency resolution policy for Gradle builds.
    #[arg(
        long,
        global = true,
        env = "TERMUX_SHADOW_DEPENDENCY_POLICY",
        value_enum,
        help_heading = "Global options"
    )]
    pub dependency_policy: Option<DependencyPolicy>,

    /// Require locked dependencies and prohibit network access.
    #[arg(
        long,
        global = true,
        conflicts_with = "online",
        help_heading = "Global options"
    )]
    pub offline: bool,

    /// Resolve locked dependencies normally and permit repository access.
    #[arg(
        long,
        global = true,
        conflicts_with = "offline",
        help_heading = "Global options"
    )]
    pub online: bool,

    /// Permit cache-first mode to retry a missing dependency through the network.
    #[arg(long, global = true, help_heading = "Global options")]
    pub allow_network: bool,

    /// Emit machine-readable JSON; dev automatically uses the compact agent contract.
    #[arg(
        long,
        global = true,
        conflicts_with = "human",
        help_heading = "Global options"
    )]
    pub json: bool,

    /// Emit the compact, decision-focused JSON contract for coding agents.
    #[arg(
        long,
        global = true,
        conflicts_with_all = ["verbose", "human"],
        help_heading = "Global options"
    )]
    pub agent: bool,

    /// Force human output, overriding a workspace output default.
    #[arg(
        long,
        global = true,
        conflicts_with_all = ["json", "agent"],
        help_heading = "Global options"
    )]
    pub human: bool,

    /// Emit detailed JSON metadata; human mode also prints captured Gradle output.
    #[arg(short, long, global = true, help_heading = "Global options")]
    pub verbose: bool,

    /// Stable idempotency key for a Worker request. Normally generated automatically.
    #[arg(
        long,
        global = true,
        env = "TERMUX_SHADOW_REQUEST_ID",
        value_name = "ID",
        help_heading = "Global options"
    )]
    pub request_id: Option<String>,

    #[command(subcommand)]
    pub command: Command,
}

#[derive(Debug, Subcommand)]
pub enum Command {
    /// Create an isolated project and return its complete coding handoff.
    New(NewArgs),
    /// Validate project identity, tools, collisions, and optionally the package.
    Doctor(DoctorArgs),
    /// Build and validate a deterministic .shadowpkg without publishing it.
    Build(BuildArgs),
    /// Build, atomically publish, reconcile, and confirm the exact package SHA.
    Publish(PublishArgs),
    /// Publish a release with an explicit increasing version.
    Upgrade(UpgradeArgs),
    /// Run the complete source-to-healthy-runtime loop with automatic resume and versioning.
    #[command(visible_aliases = ["retry", "resume"])]
    Dev(DevArgs),
    /// Build, publish, and optionally activate through one resumable deployment request.
    Deploy(DeployArgs),
    /// Resolve, lock, import, vendor, inspect, or clean dependency caches.
    Deps(DepsArgs),
    /// Convert an existing Android application module into an isolated Shadow plugin project.
    ImportAndroid(ImportAndroidArgs),
    /// Query platform health, receipts, registry pointers, and generations.
    #[command(alias = "list")]
    Status(StatusArgs),
    /// Launch or activate a plugin and wait for the exact health result.
    Run(LaunchArgs),
    /// Launch a plugin and execute its declared UI smoke-test steps.
    TestUi(LaunchArgs),
    /// Health-activate the retained previous generation.
    Rollback(LaunchArgs),
    /// Disable launches while preserving managed versions and evidence.
    Disable(PluginArg),
    /// Re-enable a plugin; the next run performs a health activation.
    Enable(PluginArg),
    /// Delete all managed versions of one logical plugin.
    #[command(alias = "remove")]
    Delete(DeleteArgs),
    /// Ask the Host to reconcile inbox and registered package integrity now.
    Refresh,
    /// Print the current project's single identity/config source.
    Config,
    /// Remove only project-local generated build output.
    Clean,
    /// Stop the reusable Gradle worker used for fast iterative builds.
    Stop,
    /// Update project tooling from the installed template without touching identity or business code.
    Sync(SyncArgs),
    /// Show resolved project, template, toolchain, Host, and control paths.
    Info,
    /// Return a compact, revision-aware development context capsule.
    Context(ContextArgs),
    /// Read the bounded summary or complete, secret-redacted files for one operation.
    Evidence(EvidenceArgs),
    /// Internal native Worker server. Not an operator command.
    #[command(name = "__worker", hide = true)]
    Worker(WorkerArgs),
}

#[derive(Debug, Clone, Copy, ValueEnum, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "kebab-case")]
pub enum DependencyPolicy {
    /// Resolve only from the selected locked cache.
    Offline,
    /// Try the selected cache first; network fallback requires explicit approval.
    #[default]
    CacheFirst,
    /// Resolve normally from configured repositories and update the shared cache.
    Online,
}

impl DependencyPolicy {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Offline => "offline",
            Self::CacheFirst => "cache-first",
            Self::Online => "online",
        }
    }
}

impl Cli {
    pub fn effective_dependency_policy(
        &self,
        workspace_default: Option<DependencyPolicy>,
    ) -> DependencyPolicy {
        if self.offline {
            DependencyPolicy::Offline
        } else if self.online {
            DependencyPolicy::Online
        } else {
            self.dependency_policy
                .or(workspace_default)
                .unwrap_or_default()
        }
    }
}

#[derive(Debug, Args)]
pub struct NewArgs {
    /// Lowercase logical slug, for example notes or image-tools.
    pub slug: String,

    /// Human-readable plugin name.
    pub display_name: Option<String>,

    /// Destination directory. Defaults to ~/termux-shadow-<slug>.
    #[arg(long, value_name = "PATH")]
    pub target: Option<PathBuf>,
    /// Stable logical plugin ID written to shadow-plugin.properties.
    #[arg(long, value_name = "ID")]
    pub plugin_id: Option<String>,
    /// Stable Shadow loader part key; must be unique in the Host registry.
    #[arg(long, value_name = "KEY")]
    pub part_key: Option<String>,
    /// Java/Kotlin package for generated business source.
    #[arg(long, value_name = "PACKAGE")]
    pub namespace: Option<String>,
    /// Simple generated Activity class name, without the namespace.
    #[arg(long, value_name = "CLASS")]
    pub activity: Option<String>,
    /// Isolated Android resource package ID; auto allocates a collision-free ID.
    #[arg(long, default_value = "auto", value_name = "auto|0xNN")]
    pub resource_id: String,
    /// Plugin description stored in schema-2 package metadata.
    #[arg(long, value_name = "TEXT")]
    pub description: Option<String>,
    /// Build, publish, reconcile, and confirm registration after creation.
    #[arg(long)]
    pub publish: bool,
    /// Permit source recovery for a pluginId already present in the live registry.
    #[arg(long)]
    pub allow_existing: bool,
    /// Derive and print identity without writing anything.
    #[arg(long)]
    pub dry_run: bool,
}

#[derive(Debug, Args)]
pub struct DepsArgs {
    /// Apply the read-only audit command to every configured workspace project.
    #[arg(long, global = true)]
    pub workspace: bool,
    #[command(subcommand)]
    pub command: DepsCommand,
}

#[derive(Debug, Subcommand)]
pub enum DepsCommand {
    /// Resolve every resolvable project configuration and optionally commit lock metadata.
    Resolve(DepsResolveArgs),
    /// Snapshot locked dependency cache entries into project-local vendor storage.
    Vendor(DepsVendorArgs),
    /// Import an existing Gradle dependency cache into the shared managed cache.
    ImportGradleCache(DepsImportArgs),
    /// Report dependency policy, lock validity, and cache layers.
    Status,
    /// Audit dependency declarations, lock integrity, and reproducible cache availability.
    Audit,
    /// Remove project vendor data or, with confirmation, the shared managed cache.
    Clean(DepsCleanArgs),
}

#[derive(Debug, Args, Clone)]
pub struct DepsResolveArgs {
    /// Explicitly permit repository access for this resolution.
    #[arg(long)]
    pub allow_network: bool,
    /// Commit Gradle lockfiles and the signed-input dependency manifest.
    #[arg(long)]
    pub lock: bool,
    /// Ignore cached dynamic/changing-module metadata while resolving online.
    #[arg(long, requires = "allow_network")]
    pub refresh: bool,
}

#[derive(Debug, Args, Clone)]
pub struct DepsVendorArgs {
    /// Recreate the vendor snapshot even when its manifest is current.
    #[arg(long)]
    pub refresh: bool,
}

#[derive(Debug, Args, Clone)]
pub struct DepsImportArgs {
    /// Gradle user home to import. Defaults to ~/.gradle.
    #[arg(long, value_name = "PATH")]
    pub from: Option<PathBuf>,
    /// Report the planned cache merge without writing files.
    #[arg(long)]
    pub dry_run: bool,
}

#[derive(Debug, Args, Clone)]
pub struct DepsCleanArgs {
    /// Remove the current project's vendor cache snapshot.
    #[arg(long)]
    pub vendor: bool,
    /// Remove the managed shared Gradle cache.
    #[arg(long, requires = "yes")]
    pub shared: bool,
    /// Confirm shared-cache deletion.
    #[arg(long)]
    pub yes: bool,
}

#[derive(Debug, Args)]
pub struct ImportAndroidArgs {
    /// Existing Android project or application-module directory.
    pub source: PathBuf,
    /// Lowercase logical slug for the generated Shadow project.
    #[arg(long)]
    pub slug: String,
    /// Human-readable plugin name.
    #[arg(long, value_name = "NAME")]
    pub display_name: Option<String>,
    /// Destination project path.
    #[arg(long, value_name = "PATH")]
    pub target: Option<PathBuf>,
    /// Override the generated logical plugin ID.
    #[arg(long, value_name = "ID")]
    pub plugin_id: Option<String>,
    /// Override the generated Shadow part key.
    #[arg(long, value_name = "KEY")]
    pub part_key: Option<String>,
    /// Override the migrated Java/Kotlin namespace.
    #[arg(long, value_name = "PACKAGE")]
    pub namespace: Option<String>,
    /// Resource package ID, or auto for collision-free allocation.
    #[arg(long, default_value = "auto", value_name = "auto|0xNN")]
    pub resource_id: String,
    /// Analyze and print a migration report without writing a project.
    #[arg(long)]
    pub dry_run: bool,
}

#[derive(Debug, Args, Clone, Copy)]
pub struct DoctorArgs {
    /// Diagnose every project declared by .shadow-workspace.toml.
    #[arg(long)]
    pub workspace: bool,
    /// Check config and source only; do not require Android or Termux tools.
    #[arg(long)]
    pub project_only: bool,
    /// Require the real Termux publisher environment.
    #[arg(long, hide = true)]
    pub publish: bool,
    /// Run Gradle package/resource validation after fast checks.
    #[arg(long)]
    pub full: bool,
    /// Ignore the native validated-artifact cache.
    #[arg(long, requires = "full")]
    pub fresh: bool,
    /// Suppress successful checks and return only warnings/failures plus the summary.
    #[arg(long)]
    pub failures_only: bool,
}

#[derive(Debug, Args, Clone)]
pub struct BuildArgs {
    #[arg(long, value_name = "N")]
    pub version_code: Option<u64>,
    #[arg(long, value_name = "NAME")]
    pub version_name: Option<String>,
    /// Ignore the native validated-artifact cache.
    #[arg(long)]
    pub fresh: bool,
}

#[derive(Debug, Args, Clone)]
pub struct PublishArgs {
    #[command(flatten)]
    pub build: BuildArgs,
    /// Return after the atomic inbox publication instead of confirming registration.
    #[arg(long)]
    pub no_wait: bool,
    /// Registration timeout in seconds.
    #[arg(long, default_value_t = 45, value_name = "SECONDS")]
    pub timeout: u64,
    /// Allow a versionCode that is not greater than the highest registered version.
    #[arg(long)]
    pub allow_downgrade: bool,
}

#[derive(Debug, Args)]
pub struct UpgradeArgs {
    pub version_code: u64,
    pub version_name: String,
    /// Registration timeout in seconds.
    #[arg(long, default_value_t = 45, value_name = "SECONDS")]
    pub timeout: u64,
}

#[derive(Debug, Args, Clone)]
pub struct DevArgs {
    /// Run the resumable development loop for every configured workspace project.
    #[arg(long, conflicts_with = "watch")]
    pub workspace: bool,
    /// Watch build inputs, debounce changes, and submit a fresh resumable Worker request.
    #[arg(long, conflicts_with = "workspace")]
    pub watch: bool,
    /// Print added, modified, and removed build inputs before each watched deployment.
    #[arg(long, requires = "watch")]
    pub diff: bool,
    /// Filesystem-event debounce window. Defaults to 500 ms.
    #[arg(long, value_name = "MILLISECONDS", requires = "watch")]
    pub debounce_ms: Option<u64>,
    /// Legacy compatibility flag. Runtime health is already part of dev by default.
    #[arg(long, hide = true, conflicts_with = "no_run")]
    pub run: bool,
    /// Stop after registration instead of activating and proving runtime health.
    #[arg(long, conflicts_with = "run")]
    pub no_run: bool,
    /// Override the automatically incremented semantic version name.
    #[arg(long, value_name = "NAME")]
    pub version_name: Option<String>,
    /// Semantic version policy when version-name is not supplied.
    #[arg(long, value_enum, default_value_t = BumpKind::Patch)]
    pub bump: BumpKind,
    /// Ignore the validated-artifact cache for the build stage.
    #[arg(long)]
    pub fresh: bool,
    /// Registration and runtime-health timeout in seconds.
    #[arg(long, default_value_t = 45, value_name = "SECONDS")]
    pub timeout: u64,
}

#[derive(Debug, Args, Clone)]
pub struct DeployArgs {
    /// Semantic version policy when version-name is not supplied.
    #[arg(long, value_enum, default_value_t = BumpKind::Patch)]
    pub bump: BumpKind,
    /// Explicit version name; conflicts with --bump.
    #[arg(long, value_name = "NAME", conflicts_with = "bump")]
    pub version_name: Option<String>,
    /// Launch and require first-frame/process-stability health proof.
    #[arg(long)]
    pub run: bool,
    /// Ignore the validated-artifact cache for the build stage.
    #[arg(long)]
    pub fresh: bool,
    /// Registration and runtime-health timeout in seconds.
    #[arg(long, default_value_t = 45, value_name = "SECONDS")]
    pub timeout: u64,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum BumpKind {
    Patch,
    Minor,
    Major,
}

#[derive(Debug, Args, Clone)]
pub struct StatusArgs {
    /// Show all registered plugins regardless of project context.
    ///
    /// By default, status shows only the plugin matching the current project. If no
    /// project context exists, status safely falls back to all registered plugins.
    #[arg(long)]
    pub all: bool,
    /// Wait for the current project's last publish receipt SHA.
    #[arg(long)]
    pub wait: bool,
    /// Registration wait timeout in seconds.
    #[arg(long, default_value_t = 45, value_name = "SECONDS")]
    pub timeout: u64,
    /// Print the raw health and registry JSON reports.
    #[arg(long)]
    pub raw: bool,
    /// Emit only the current development-loop state and fingerprints (default for JSON).
    #[arg(long, conflicts_with = "raw")]
    pub compact: bool,
    /// Include retained generation history. This can be large and is never implicit in JSON mode.
    #[arg(long, conflicts_with_all = ["compact", "raw"])]
    pub history: bool,
    /// Read another managed Shadow root (read-only; useful for tests).
    #[arg(long, value_name = "PATH", hide = true)]
    pub shadow_home: Option<PathBuf>,
}

#[derive(Debug, Args)]
pub struct LaunchArgs {
    /// Defaults to pluginId from the current project.
    pub plugin_id: Option<String>,
    /// Return as soon as the Host accepts the launch request.
    #[arg(long)]
    pub no_wait: bool,
    /// Relaunch an already proven active generation instead of returning ALREADY_ACTIVE.
    #[arg(long)]
    pub force: bool,
    /// Run the declarative UI smoke test after the first frame.
    #[arg(long)]
    pub smoke: bool,
    /// Smoke-test JSON file. Defaults to shadow-smoke.json in the current project.
    #[arg(long, value_name = "PATH", requires = "smoke")]
    pub smoke_file: Option<PathBuf>,
    /// Health-result timeout in seconds.
    #[arg(long, default_value_t = 30, value_name = "SECONDS")]
    pub timeout: u64,
}

#[derive(Debug, Args)]
pub struct PluginArg {
    /// Defaults to pluginId from the current project.
    pub plugin_id: Option<String>,
}

#[derive(Debug, Args)]
pub struct DeleteArgs {
    /// Defaults to pluginId from the current project.
    pub plugin_id: Option<String>,
    /// Confirm removal of every managed version. Audit evidence remains preserved.
    #[arg(long)]
    pub yes: bool,
}

#[derive(Debug, Args)]
pub struct SyncArgs {
    /// Print the tooling files that would change without writing them.
    #[arg(long)]
    pub dry_run: bool,
}

#[derive(Debug, Args)]
pub struct ContextArgs {
    /// Host-only compatibility cursor. Prefer --since-cursor when local source changes matter.
    #[arg(
        long,
        value_name = "REVISION",
        conflicts_with_all = ["since_cursor", "resume"]
    )]
    pub since_revision: Option<u64>,
    /// Return changed=false when the complete development context still matches this cursor.
    #[arg(long, value_name = "CURSOR", conflicts_with = "resume")]
    pub since_cursor: Option<String>,
    /// Resume from a private per-project cursor under ~/.termux-shadow/sessions.
    #[arg(long)]
    pub resume: bool,
    /// Emit the compact decision capsule. Kept explicit for discoverability.
    #[arg(long, default_value_t = true)]
    pub compact: bool,
}

#[derive(Debug, Args)]
pub struct EvidenceArgs {
    /// Evidence identifier returned by a Worker-backed command.
    pub operation_id: String,
    /// Return only parsed diagnostics.
    #[arg(long, conflicts_with_all = ["tail", "full"])]
    pub diagnostics: bool,
    /// Return the last N lines from stdout and stderr.
    #[arg(long, value_name = "LINES", conflicts_with_all = ["diagnostics", "full"])]
    pub tail: Option<usize>,
    /// Return every evidence file with its complete text.
    #[arg(long, conflicts_with_all = ["diagnostics", "tail"])]
    pub full: bool,
}

#[derive(Debug, Args, Clone)]
pub struct WorkerArgs {
    /// Override the default 60-minute idle timeout.
    #[arg(
        long,
        env = "TERMUX_SHADOW_WORKER_IDLE_SECONDS",
        default_value_t = 3600
    )]
    pub idle_timeout_seconds: u64,
}

#[cfg(test)]
mod tests {
    use super::{Cli, Command, DependencyPolicy};
    use clap::Parser;

    #[test]
    fn agent_is_a_global_deploy_option() {
        let cli = Cli::try_parse_from(["shadow-plugin", "deploy", "--agent", "--run"]).unwrap();
        assert!(cli.agent);
        assert!(!cli.json);
        assert!(matches!(cli.command, Command::Deploy(_)));
    }

    #[test]
    fn status_history_is_explicit_and_cannot_be_combined_with_compact() {
        let cli = Cli::try_parse_from(["shadow-plugin", "status", "--history", "--json"]).unwrap();
        assert!(matches!(cli.command, Command::Status(args) if args.history));
        assert!(
            Cli::try_parse_from(["shadow-plugin", "status", "--history", "--compact"]).is_err()
        );
    }

    #[test]
    fn retry_and_resume_are_dev_aliases_and_no_run_is_explicit() {
        assert!(matches!(
            Cli::try_parse_from(["shadow-plugin", "retry"])
                .unwrap()
                .command,
            Command::Dev(_)
        ));
        assert!(matches!(
            Cli::try_parse_from(["shadow-plugin", "resume"])
                .unwrap()
                .command,
            Command::Dev(_)
        ));
        let cli = Cli::try_parse_from(["shadow-plugin", "dev", "--no-run"]).unwrap();
        assert!(matches!(cli.command, Command::Dev(args) if args.no_run && !args.run));
    }

    #[test]
    fn dependency_policy_defaults_to_cache_first_and_supports_explicit_network_modes() {
        let default = Cli::try_parse_from(["shadow-plugin", "deps", "status"]).unwrap();
        assert_eq!(
            default.effective_dependency_policy(None),
            DependencyPolicy::CacheFirst
        );
        let offline =
            Cli::try_parse_from(["shadow-plugin", "--offline", "deps", "status"]).unwrap();
        assert_eq!(
            offline.effective_dependency_policy(None),
            DependencyPolicy::Offline
        );
        let online = Cli::try_parse_from(["shadow-plugin", "deps", "status", "--online"]).unwrap();
        assert_eq!(
            online.effective_dependency_policy(None),
            DependencyPolicy::Online
        );
    }

    #[test]
    fn workspace_default_policy_is_lower_precedence_than_cli_modes() {
        let default = Cli::try_parse_from(["shadow-plugin", "deps", "status"]).unwrap();
        assert_eq!(
            default.effective_dependency_policy(Some(DependencyPolicy::Offline)),
            DependencyPolicy::Offline
        );
        let online = Cli::try_parse_from(["shadow-plugin", "--online", "deps", "status"]).unwrap();
        assert_eq!(
            online.effective_dependency_policy(Some(DependencyPolicy::Offline)),
            DependencyPolicy::Online
        );
    }
}
