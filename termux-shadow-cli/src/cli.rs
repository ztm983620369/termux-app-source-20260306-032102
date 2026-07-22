use std::path::PathBuf;

use clap::{Args, Parser, Subcommand, ValueEnum};

#[derive(Debug, Parser)]
#[command(
    name = "shadow-plugin",
    version,
    about = "Fast native workflow for Termux Shadow plugins",
    long_about = "Create, diagnose, build, publish, query, launch, upgrade, and recover managed Termux Shadow plugins from one native command."
)]
pub struct Cli {
    /// Plugin project. Defaults to the nearest parent containing shadow-plugin.properties.
    #[arg(
        long,
        global = true,
        env = "SHADOW_PLUGIN_PROJECT",
        value_name = "PATH"
    )]
    pub project: Option<PathBuf>,

    /// Canonical scaffold template.
    #[arg(
        long,
        global = true,
        env = "TERMUX_SHADOW_TEMPLATE",
        value_name = "PATH"
    )]
    pub template: Option<PathBuf>,

    /// Portable Android toolchain root.
    #[arg(
        long,
        global = true,
        env = "TERMUX_SHADOW_ANDROID_TOOLCHAIN",
        value_name = "PATH"
    )]
    pub toolchain: Option<PathBuf>,

    /// Emit machine-readable JSON; dev automatically uses the compact agent contract.
    #[arg(long, global = true)]
    pub json: bool,

    /// Emit the compact, decision-focused JSON contract for coding agents.
    #[arg(long, global = true, conflicts_with = "verbose")]
    pub agent: bool,

    /// Emit detailed JSON metadata; human mode also prints captured Gradle output.
    #[arg(short, long, global = true)]
    pub verbose: bool,

    /// Stable idempotency key for a Worker request. Normally generated automatically.
    #[arg(
        long,
        global = true,
        env = "TERMUX_SHADOW_REQUEST_ID",
        value_name = "ID"
    )]
    pub request_id: Option<String>,

    #[command(subcommand)]
    pub command: Command,
}

#[derive(Debug, Subcommand)]
pub enum Command {
    /// Create an isolated plugin project from the installed template.
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
    /// Query platform health, receipts, registry pointers, and generations.
    #[command(alias = "list")]
    Status(StatusArgs),
    /// Launch or activate a plugin and wait for the exact health result.
    Run(LaunchArgs),
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

#[derive(Debug, Args)]
pub struct NewArgs {
    /// Lowercase logical slug, for example notes or image-tools.
    pub slug: String,

    /// Human-readable plugin name.
    pub display_name: Option<String>,

    #[arg(long, value_name = "PATH")]
    pub target: Option<PathBuf>,
    #[arg(long, value_name = "ID")]
    pub plugin_id: Option<String>,
    #[arg(long, value_name = "KEY")]
    pub part_key: Option<String>,
    #[arg(long, value_name = "PACKAGE")]
    pub namespace: Option<String>,
    #[arg(long, value_name = "CLASS")]
    pub activity: Option<String>,
    #[arg(long, default_value = "auto", value_name = "auto|0xNN")]
    pub resource_id: String,
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

#[derive(Debug, Args, Clone, Copy)]
pub struct DoctorArgs {
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
    /// Include every logical plugin instead of only the current project.
    #[arg(long)]
    pub all: bool,
    /// Wait for the current project's last publish receipt SHA.
    #[arg(long)]
    pub wait: bool,
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
    /// Return changed=false when the Host registry has not advanced beyond this revision.
    #[arg(long, value_name = "REVISION")]
    pub since_revision: Option<u64>,
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
    use super::{Cli, Command};
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
}
