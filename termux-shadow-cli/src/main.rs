mod build;
mod cache;
mod capsule;
mod cli;
mod config;
mod context;
mod control;
mod dependency;
mod dev;
mod doctor;
mod errors;
mod evidence;
mod fsutil;
mod history;
mod import_android;
mod runtime_artifacts;
mod runtime_crash;
mod scaffold;
mod status;
mod sync;
mod watch;
mod worker;
mod workspace;

use std::ffi::OsString;
use std::process::ExitCode;

use anyhow::Result;
use clap::Parser;
use clap::error::ErrorKind;

use crate::cli::{Cli, Command};
use crate::context::{AppContext, AppContextOptions};
use crate::workspace::{Workspace, WorkspaceOutput};

fn main() -> ExitCode {
    let arguments = std::env::args_os().collect::<Vec<_>>();
    let json_requested = arguments
        .iter()
        .any(|argument| argument == "--json" || argument == "--agent");
    let fallback_action = inferred_action();
    let mut cli = match Cli::try_parse_from(&arguments) {
        Ok(cli) => cli,
        Err(error)
            if matches!(
                error.kind(),
                ErrorKind::DisplayHelp | ErrorKind::DisplayVersion
            ) =>
        {
            print!("{error}");
            return ExitCode::SUCCESS;
        }
        Err(error) => {
            errors::emit_usage(error.to_string(), json_requested, fallback_action);
            return ExitCode::FAILURE;
        }
    };
    let workspace = match Workspace::discover(
        cli.workspace_config.as_deref(),
        cli.project.as_deref(),
    )
    .and_then(|workspace| {
        apply_workspace_defaults(&mut cli, workspace.as_ref())?;
        Ok(workspace)
    }) {
        Ok(workspace) => workspace,
        Err(error) => {
            errors::emit(&error, json_requested, fallback_action);
            return ExitCode::FAILURE;
        }
    };
    let action = command_name(&cli.command);
    let json = cli.json || cli.agent;
    match run(cli, &arguments, workspace) {
        Ok(0) => ExitCode::SUCCESS,
        Ok(_) => ExitCode::FAILURE,
        Err(error) => {
            errors::emit(&error, json, action);
            ExitCode::FAILURE
        }
    }
}

fn run(cli: Cli, arguments: &[OsString], workspace: Option<Workspace>) -> Result<i32> {
    let json = cli.json || cli.agent;
    let dependency_policy = cli.effective_dependency_policy(None);
    let context = AppContext::new(AppContextOptions {
        project_override: cli.project.clone(),
        template_override: cli.template.clone(),
        toolchain_override: cli.toolchain.clone(),
        json,
        verbose: cli.verbose,
        dependency_policy,
        allow_network: cli.allow_network,
        workspace,
    })?;

    if let Command::Worker(args) = &cli.command {
        worker::serve(&context, args.clone())?;
        return Ok(0);
    }
    if let Some(kind) = workspace::batch_kind(&cli.command) {
        return workspace::run_batch(&context, kind, arguments);
    }
    if let Command::Dev(args) = &cli.command
        && args.watch
    {
        return watch::run(&context, arguments, args.diff, args.debounce_ms);
    }
    if matches!(&cli.command, Command::Stop) && !worker::is_direct() {
        return worker::stop(&context, arguments, cli.request_id.as_deref());
    }
    if worker::should_route(&cli.command) && !worker::is_direct() {
        return worker::execute(
            &context,
            command_name(&cli.command),
            arguments,
            cli.request_id.as_deref(),
        );
    }

    match cli.command {
        Command::New(args) => scaffold::run(&context, args),
        Command::Doctor(args) => doctor::run(&context, args),
        Command::Build(args) => build::run_build(&context, args),
        Command::Publish(args) => build::run_publish(&context, args),
        Command::Upgrade(args) => build::run_upgrade(&context, args),
        Command::Dev(args) => dev::run(&context, args),
        Command::Deploy(args) => dev::run_deploy(&context, args),
        Command::Deps(args) => dependency::run(&context, args),
        Command::ImportAndroid(args) => import_android::run(&context, args),
        Command::Status(args) => status::run(&context, args),
        Command::Run(args) => control::run_launch(&context, args, false),
        Command::TestUi(mut args) => {
            args.smoke = true;
            args.force = true;
            control::run_launch(&context, args, false)
        }
        Command::Rollback(args) => control::run_launch(&context, args, true),
        Command::Disable(args) => control::run_mutation(&context, "disable", args.plugin_id),
        Command::Enable(args) => control::run_mutation(&context, "enable", args.plugin_id),
        Command::Delete(args) => control::run_delete(&context, args),
        Command::Refresh => control::run_refresh(&context),
        Command::Config => config::print_current(&context),
        Command::Clean => build::run_clean(&context),
        Command::Stop => build::run_stop(&context),
        Command::Sync(args) => sync::run(&context, args),
        Command::Info => context.print_info(),
        Command::Context(args) => capsule::run(&context, args),
        Command::Evidence(args) => evidence::run(&context, args),
        Command::Worker(_) => unreachable!(),
    }?;
    Ok(0)
}

fn apply_workspace_defaults(cli: &mut Cli, workspace: Option<&Workspace>) -> Result<()> {
    if matches!(&cli.command, Command::Dev(args) if args.watch) && !cli.json && !cli.agent {
        // A watcher is an event stream. Keep it human unless the caller explicitly requested a
        // machine contract, which watch::run rejects to preserve the one-document JSON invariant.
        cli.human = true;
    }
    let Some(workspace) = workspace else {
        if cli
            .project
            .as_deref()
            .and_then(|path| path.to_str())
            .is_some_and(|value| value.starts_with('@'))
        {
            anyhow::bail!(
                "WORKSPACE_REQUIRED: --project @NAME requires a {0}; pass --workspace-config PATH or run inside a configured workspace",
                workspace::WORKSPACE_FILE
            );
        }
        return Ok(());
    };
    if let Some(project) = cli.project.take() {
        cli.project = Some(workspace.resolve_project_argument(project)?);
    }
    let defaults = workspace.defaults();
    if cli.template.is_none() {
        cli.template.clone_from(&defaults.template);
    }
    if cli.toolchain.is_none() {
        cli.toolchain.clone_from(&defaults.toolchain);
    }
    if cli.dependency_policy.is_none() {
        cli.dependency_policy = defaults.dependency_policy;
    }
    if !cli.json && !cli.agent && !cli.human && !cli.verbose {
        match defaults.output {
            Some(WorkspaceOutput::Json) => cli.json = true,
            Some(WorkspaceOutput::Agent) => cli.agent = true,
            Some(WorkspaceOutput::Human) | None => {}
        }
    }
    Ok(())
}

fn command_name(command: &Command) -> &'static str {
    match command {
        Command::New(_) => "new",
        Command::Doctor(_) => "doctor",
        Command::Build(_) => "build",
        Command::Publish(_) => "publish",
        Command::Upgrade(_) => "upgrade",
        Command::Dev(_) => "dev",
        Command::Deploy(_) => "deploy",
        Command::Deps(_) => "deps",
        Command::ImportAndroid(_) => "import-android",
        Command::Status(_) => "status",
        Command::Run(_) => "run",
        Command::TestUi(_) => "test-ui",
        Command::Rollback(_) => "rollback",
        Command::Disable(_) => "disable",
        Command::Enable(_) => "enable",
        Command::Delete(_) => "delete",
        Command::Refresh => "refresh",
        Command::Config => "config",
        Command::Clean => "clean",
        Command::Stop => "stop",
        Command::Sync(_) => "sync",
        Command::Info => "info",
        Command::Context(_) => "context",
        Command::Evidence(_) => "evidence",
        Command::Worker(_) => "worker",
    }
}

fn inferred_action() -> &'static str {
    for argument in std::env::args().skip(1) {
        match argument.as_str() {
            "new" => return "new",
            "doctor" => return "doctor",
            "build" => return "build",
            "publish" => return "publish",
            "upgrade" => return "upgrade",
            "dev" | "retry" | "resume" => return "dev",
            "deploy" => return "deploy",
            "deps" => return "deps",
            "import-android" => return "import-android",
            "status" | "list" => return "status",
            "run" => return "run",
            "test-ui" => return "test-ui",
            "rollback" => return "rollback",
            "disable" => return "disable",
            "enable" => return "enable",
            "delete" => return "delete",
            "refresh" => return "refresh",
            "config" => return "config",
            "clean" => return "clean",
            "stop" => return "stop",
            "sync" => return "sync",
            "info" => return "info",
            "context" => return "context",
            "evidence" => return "evidence",
            _ => {}
        }
    }
    "unknown"
}
