use std::collections::{BTreeMap, BTreeSet};
use std::env;
use std::ffi::{OsStr, OsString};
use std::fs::{self, OpenOptions};
use std::io::{ErrorKind, Read, Write};
use std::os::fd::AsRawFd;
use std::os::unix::fs::MetadataExt;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::net::{UnixListener, UnixStream};
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Command as ProcessCommand, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, anyhow, bail};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use sha2::{Digest, Sha256};

use crate::cli::{Command, WorkerArgs};
use crate::context::{AppContext, is_termux_home};
use crate::evidence::{EvidenceCapture, EvidenceRef};
use crate::fsutil::{sha256_file, write_atomic};

pub const PROTOCOL_VERSION: u32 = 1;
const DEFAULT_IDLE_SECONDS: u64 = 3600;
const MAX_FRAME_BYTES: usize = 32 * 1024 * 1024;
const MAX_CACHED_REQUESTS: usize = 128;
const START_TIMEOUT: Duration = Duration::from_secs(8);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(2);
// A Worker can retain a live socket while Android has temporarily frozen the Termux UID.
// Never wait for the full build timeout during the compatibility ping: a short timeout lets the
// client ask the Android Supervisor to touch the foreground service and thaw the Worker before
// retrying. The real request still keeps RESPONSE_TIMEOUT for legitimate cold builds.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(1);
const RESPONSE_TIMEOUT: Duration = Duration::from_secs(15 * 60);
const INSPECT_TIMEOUT: Duration = Duration::from_millis(250);
const DIRECT_ENV: &str = "TERMUX_SHADOW_DIRECT";
const REQUIRED_ENV: &str = "TERMUX_SHADOW_WORKER_REQUIRED";
const MANAGED_GRADLE_ENV: &str = "TERMUX_SHADOW_MANAGED_GRADLE";
const WORKER_DIR: &str = "worker";
const SOCKET_NAME: &str = "shadow-plugin.sock";
const STATE_NAME: &str = "state.json";
const LOCK_NAME: &str = "worker.lock";

static ID_SEQUENCE: AtomicU64 = AtomicU64::new(1);

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkerInfo {
    pub status: String,
    pub pid: Option<u32>,
    pub protocol_version: u32,
    pub cli_version: String,
    pub idle_timeout_seconds: u64,
    pub idle_remaining_seconds: u64,
    pub requests_served: u64,
    pub gradle_daemon: String,
    pub gradle_daemon_pid: Option<u32>,
    #[serde(default)]
    pub gradle_daemon_managed: bool,
    pub started_at: Option<u64>,
    pub last_request_at: Option<u64>,
    pub socket: String,
    pub binary_sha256: Option<String>,
    pub execution_mode: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub current_request_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub current_operation_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub current_action: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RpcRequest {
    protocol_version: u32,
    request_id: String,
    action: String,
    project: Option<String>,
    arguments: Vec<String>,
    environment: BTreeMap<String, String>,
    json: bool,
    client_version: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RpcResponse {
    protocol_version: u32,
    request_id: String,
    request_hash: Option<String>,
    ok: bool,
    exit_code: i32,
    stdout: String,
    stderr: String,
    operation_id: Option<String>,
    evidence: Option<EvidenceRef>,
    worker: WorkerInfo,
    error_code: Option<String>,
    message: Option<String>,
}

struct RuntimeState {
    started_at: u64,
    last_request_at: u64,
    last_activity: Instant,
    idle_timeout: Duration,
    requests_served: u64,
    gradle_daemon_pid: Option<u32>,
    gradle_daemon_managed: bool,
    gradle_version_marker: Option<String>,
    binary_sha256: Option<String>,
    current_request_id: Option<String>,
    current_operation_id: Option<String>,
    current_action: Option<String>,
}

pub fn is_direct() -> bool {
    env::var_os(DIRECT_ENV).is_some()
}

pub fn should_route(command: &Command) -> bool {
    match command {
        Command::Build(_)
        | Command::Publish(_)
        | Command::Upgrade(_)
        | Command::Dev(_)
        | Command::Deploy(_)
        | Command::Clean => true,
        Command::Doctor(args) => args.full,
        Command::Run(args) | Command::Rollback(args) => args.plugin_id.is_none(),
        _ => false,
    }
}

pub fn execute(
    context: &AppContext,
    action: &str,
    arguments: &[OsString],
    request_id: Option<&str>,
) -> Result<i32> {
    let request = build_request(context, action, arguments, request_id)?;
    let agent = request_is_agent(&request);
    match connect_compatible(context, &request) {
        Ok(response) => render_response(response, context.json, context.verbose, agent, action),
        Err(first_error) => {
            if context.is_real_termux_home() && Path::new("/system/bin/am").is_file() {
                if format!("{first_error:#}").contains("WORKER_PROTOCOL_MISMATCH") {
                    let _ = crate::control::stop_build_worker(context);
                    thread::sleep(Duration::from_millis(250));
                }
                let _ = crate::control::ensure_build_worker(context);
                let started = Instant::now();
                let mut last_ensure = Instant::now();
                let mut last_error = first_error;
                while started.elapsed() < START_TIMEOUT {
                    thread::sleep(Duration::from_millis(100));
                    match connect_compatible(context, &request) {
                        Ok(response) => {
                            return render_response(
                                response,
                                context.json,
                                context.verbose,
                                agent,
                                action,
                            );
                        }
                        Err(error) => last_error = error,
                    }
                    // The Supervisor's Process.waitFor() monitor and a client-observed socket
                    // failure can race. An ENSURE delivered in that narrow window may still see
                    // the dying child as alive. Reassert the idempotent request while waiting so
                    // the service starts a replacement as soon as cleanup finishes.
                    if last_ensure.elapsed() >= Duration::from_secs(1) {
                        let _ = crate::control::ensure_build_worker(context);
                        last_ensure = Instant::now();
                    }
                }
                if worker_required() {
                    return Err(anyhow!(
                        "WORKER_UNAVAILABLE: Android Supervisor did not provide a ready Worker: {last_error:#}"
                    ));
                }
                execute_direct_fallback(context, &request, &last_error)
            } else if worker_required() {
                Err(anyhow!(
                    "WORKER_UNAVAILABLE: native Worker is unavailable and Android Supervisor cannot be reached: {first_error:#}"
                ))
            } else {
                execute_direct_fallback(context, &request, &first_error)
            }
        }
    }
}

pub fn stop(context: &AppContext, arguments: &[OsString], request_id: Option<&str>) -> Result<i32> {
    let mut request = build_request(context, "stop", arguments, request_id)?;
    request.action = "SHUTDOWN".to_owned();
    match connect_and_send(context, &request) {
        Ok(response) => {
            let code = render_response(
                response,
                context.json,
                context.verbose,
                request_is_agent(&request),
                "stop",
            )?;
            let _ = crate::control::stop_build_worker(context);
            Ok(code)
        }
        Err(error) => {
            let _ = crate::control::stop_build_worker(context);
            execute_direct_fallback(context, &request, &error)
        }
    }
}

pub fn inspect(context: &AppContext) -> WorkerInfo {
    let request = RpcRequest {
        protocol_version: PROTOCOL_VERSION,
        request_id: request_id("ping"),
        action: "PING".to_owned(),
        project: None,
        arguments: Vec::new(),
        environment: BTreeMap::new(),
        json: true,
        client_version: env!("CARGO_PKG_VERSION").to_owned(),
    };
    if let Ok(response) = connect_and_send_timeout(context, &request, INSPECT_TIMEOUT) {
        let mut worker = response.worker;
        let binary_sha256 = current_binary_sha256();
        if worker_compatibility_issue(&worker, env!("CARGO_PKG_VERSION"), binary_sha256.as_deref())
            .is_some()
        {
            worker.status = "INCOMPATIBLE".to_owned();
            worker.idle_remaining_seconds = 0;
        }
        return worker;
    }
    let state_path = state_path(context);
    if let Ok(bytes) = fs::read(&state_path)
        && let Ok(mut info) = serde_json::from_slice::<WorkerInfo>(&bytes)
    {
        let alive = info.pid.is_some_and(process_alive);
        info.status = if alive && info.status == "BUSY" {
            "BUSY".to_owned()
        } else if alive {
            "UNREACHABLE".to_owned()
        } else {
            "STALE".to_owned()
        };
        if info.status != "BUSY" {
            info.idle_remaining_seconds = 0;
        }
        return info;
    }
    stopped_info(context)
}

pub fn serve(context: &AppContext, args: WorkerArgs) -> Result<()> {
    let idle_seconds = args.idle_timeout_seconds.clamp(30, 24 * 60 * 60);
    let directory = worker_directory(context);
    create_private_dir(&directory)?;
    let lock = acquire_lock(&directory)?;
    let socket = socket_path(context);
    if socket.exists() {
        fs::remove_file(&socket).with_context(|| format!("remove stale {}", socket.display()))?;
    }
    let listener = UnixListener::bind(&socket)
        .with_context(|| format!("bind Worker socket {}", socket.display()))?;
    fs::set_permissions(&socket, fs::Permissions::from_mode(0o600))?;
    listener.set_nonblocking(true)?;
    let now = now_millis();
    let executable = env::current_exe().ok();
    let mut state = RuntimeState {
        started_at: now,
        last_request_at: now,
        last_activity: Instant::now(),
        idle_timeout: Duration::from_secs(idle_seconds),
        requests_served: 0,
        gradle_daemon_pid: None,
        gradle_daemon_managed: false,
        gradle_version_marker: None,
        binary_sha256: executable
            .as_deref()
            .and_then(|path| sha256_file(path).ok()),
        current_request_id: None,
        current_operation_id: None,
        current_action: None,
    };
    write_state(context, &state, "READY")?;

    let mut shutdown = false;
    while !shutdown && state.last_activity.elapsed() < state.idle_timeout {
        match listener.accept() {
            Ok((stream, _)) => {
                state.last_activity = Instant::now();
                state.last_request_at = now_millis();
                match handle_connection(context, stream, &mut state) {
                    Ok(requested_shutdown) => shutdown = requested_shutdown,
                    Err(error) => {
                        let _ = write_worker_log(context, &format!("request failed: {error:#}"));
                    }
                }
                state.current_request_id = None;
                state.current_operation_id = None;
                state.current_action = None;
                if state
                    .gradle_daemon_pid
                    .is_some_and(|pid| !process_alive(pid))
                {
                    state.gradle_daemon_pid = None;
                    state.gradle_daemon_managed = false;
                }
                let _ = write_state(context, &state, if shutdown { "STOPPING" } else { "READY" });
            }
            Err(error) if error.kind() == ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(100));
            }
            Err(error) => return Err(error).context("accept Worker connection"),
        }
    }

    if state.gradle_daemon_managed
        && let Some(pid) = state.gradle_daemon_pid
    {
        stop_owned_gradle_daemon(pid);
        state.gradle_daemon_pid = None;
        state.gradle_daemon_managed = false;
    }
    drop(listener);
    let _ = fs::remove_file(&socket);
    let _ = write_state(context, &state, "STOPPED");
    drop(lock);
    let _ = fs::remove_file(directory.join(LOCK_NAME));
    Ok(())
}

fn handle_connection(
    context: &AppContext,
    mut stream: UnixStream,
    state: &mut RuntimeState,
) -> Result<bool> {
    let peer = peer_uid(&stream)?;
    let current = unsafe { libc::getuid() };
    if peer != current {
        bail!("Worker rejected peer uid {peer}; expected {current}");
    }
    stream.set_read_timeout(Some(Duration::from_secs(5)))?;
    stream.set_write_timeout(Some(Duration::from_secs(10)))?;
    let frame = read_frame(&mut stream)?;
    let request: RpcRequest = serde_json::from_slice(&frame).context("parse Worker request")?;
    if request.action != "PING" {
        state.current_request_id = Some(request.request_id.clone());
        state.current_action = Some(request.action.clone());
        state.current_operation_id = None;
        write_state(context, state, "BUSY")?;
    }
    let (response, shutdown) = handle_request(context, request, state);
    let bytes = serde_json::to_vec(&response)?;
    stream.write_all(&bytes)?;
    stream.flush()?;
    Ok(shutdown)
}

fn handle_request(
    context: &AppContext,
    request: RpcRequest,
    state: &mut RuntimeState,
) -> (RpcResponse, bool) {
    if request.protocol_version != PROTOCOL_VERSION {
        return (
            protocol_error(
                context,
                state,
                &request.request_id,
                "WORKER_PROTOCOL_MISMATCH",
                format!(
                    "client protocol {} does not match Worker protocol {}",
                    request.protocol_version, PROTOCOL_VERSION
                ),
            ),
            false,
        );
    }
    if !safe_request_id(&request.request_id) {
        return (
            protocol_error(
                context,
                state,
                "invalid-request",
                "WORKER_REQUEST_INVALID",
                "requestId is not path-safe".to_owned(),
            ),
            false,
        );
    }
    if request.action == "PING" {
        return (
            RpcResponse {
                protocol_version: PROTOCOL_VERSION,
                request_id: request.request_id,
                request_hash: None,
                ok: true,
                exit_code: 0,
                stdout: String::new(),
                stderr: String::new(),
                operation_id: None,
                evidence: None,
                worker: running_info(context, state, "READY", "WORKER"),
                error_code: None,
                message: None,
            },
            false,
        );
    }
    state.requests_served = state.requests_served.saturating_add(1);
    let request_hash = request_hash(&request).ok();
    if let Ok(Some(response)) = cached_response(context, &request.request_id) {
        if response.request_hash == request_hash {
            return (response, request.action == "SHUTDOWN");
        }
        return (
            protocol_error(
                context,
                state,
                &request.request_id,
                "WORKER_REQUEST_ID_CONFLICT",
                "requestId was already used for different input".to_owned(),
            ),
            false,
        );
    }
    let shutdown = request.action == "SHUTDOWN";
    let result = validate_request(context, &request)
        .and_then(|project| execute_child(context, &request, &project, state));
    let response = match result {
        Ok(response) => response,
        Err(error) => protocol_error(
            context,
            state,
            &request.request_id,
            "WORKER_REQUEST_FAILED",
            format!("{error:#}"),
        ),
    };
    let _ = cache_response(context, &response);
    (response, shutdown)
}

fn execute_child(
    context: &AppContext,
    request: &RpcRequest,
    project: &Path,
    state: &mut RuntimeState,
) -> Result<RpcResponse> {
    let may_use_gradle = action_may_use_gradle(&request.action);
    let gradle_marker = may_use_gradle
        .then(|| gradle_version_marker(project))
        .flatten();
    let daemons_before = gradle_marker
        .as_deref()
        .map(|marker| find_gradle_daemon_pids(&context.termux_home, marker))
        .unwrap_or_default();
    let operation_id = request
        .environment
        .get("TERMUX_SHADOW_OPERATION_ID")
        .filter(|value| safe_request_id(value))
        .cloned()
        .unwrap_or_else(|| operation_id(&request.action));
    state.current_operation_id = Some(operation_id.clone());
    write_state(context, state, "BUSY")?;
    let registry_path = context.shadow_home.join("reports/registry.json");
    let before = fs::read(&registry_path).ok();
    let evidence_request = json!({
        "protocolVersion": request.protocol_version,
        "requestId": request.request_id,
        "operationId": operation_id,
        "action": request.action,
        "project": project.display().to_string(),
        "arguments": sanitized_arguments(&request.arguments),
        "clientVersion": request.client_version,
    });
    let capture = EvidenceCapture::begin(
        &context.shadow_home,
        &operation_id,
        &evidence_request,
        before.as_deref(),
        Some(project),
    )?;
    let started = Instant::now();
    let mut command = ProcessCommand::new(env::current_exe().context("resolve Worker executable")?);
    command
        .args(&request.arguments)
        .current_dir(project)
        .env_clear()
        .env(DIRECT_ENV, "1")
        .env("TERMUX_SHADOW_OPERATION_ID", &operation_id)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    for (name, value) in base_environment(context) {
        command.env(name, value);
    }
    for (name, value) in &request.environment {
        if allowed_environment(name) {
            command.env(name, value);
        }
    }
    if may_use_gradle {
        command.env(MANAGED_GRADLE_ENV, "1");
    }
    // If Android reclaims or explicitly terminates the Worker, do not leave a detached executor
    // that can publish after its supervising operation disappeared.
    unsafe {
        command.pre_exec(|| {
            if libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGTERM) != 0 {
                return Err(std::io::Error::last_os_error());
            }
            Ok(())
        });
    }
    let output = command.output().context("start direct Worker executor")?;
    let duration_ms = elapsed_ms(started);
    let after = fs::read(&registry_path).ok();
    let child_exit_code = output.status.code().unwrap_or(1);
    let json_contract_ok = !request.json || is_json_object(&output.stdout);
    let exit_code = if json_contract_ok {
        child_exit_code
    } else {
        child_exit_code.max(1)
    };
    let reference = capture.finish(
        &request.action.to_ascii_lowercase(),
        exit_code,
        &output.stdout,
        &output.stderr,
        duration_ms,
        after.as_deref(),
    )?;
    if let Some(marker) = gradle_marker {
        let daemons_after = find_gradle_daemon_pids(&context.termux_home, &marker);
        let newly_started = daemons_after.difference(&daemons_before).next().copied();
        let previous_managed = state
            .gradle_daemon_pid
            .filter(|pid| state.gradle_daemon_managed && daemons_after.contains(pid));
        state.gradle_daemon_pid = newly_started
            .or(previous_managed)
            .or_else(|| daemons_after.iter().next().copied());
        state.gradle_daemon_managed = state
            .gradle_daemon_pid
            .is_some_and(is_shadow_managed_gradle);
        state.gradle_version_marker = Some(marker);
    }
    let worker_status = if request.action == "SHUTDOWN" {
        "STOPPING"
    } else {
        "READY"
    };
    let worker = running_info(context, state, worker_status, "WORKER");
    let stdout = decorate_stdout(
        &output.stdout,
        request,
        &operation_id,
        &reference,
        &worker,
        child_exit_code,
    );
    Ok(RpcResponse {
        protocol_version: PROTOCOL_VERSION,
        request_id: request.request_id.clone(),
        request_hash: request_hash(request).ok(),
        ok: exit_code == 0,
        exit_code,
        stdout,
        stderr: String::from_utf8_lossy(&output.stderr).into_owned(),
        operation_id: Some(operation_id),
        evidence: Some(reference),
        worker,
        error_code: None,
        message: None,
    })
}

fn execute_direct_fallback(
    context: &AppContext,
    request: &RpcRequest,
    worker_error: &anyhow::Error,
) -> Result<i32> {
    let project = validate_request(context, request)?;
    let operation_id = operation_id(&request.action);
    let registry_path = context.shadow_home.join("reports/registry.json");
    let before = fs::read(&registry_path).ok();
    let evidence_request = json!({
        "protocolVersion": request.protocol_version,
        "requestId": request.request_id,
        "operationId": operation_id,
        "action": request.action,
        "project": project.display().to_string(),
        "arguments": sanitized_arguments(&request.arguments),
        "executionMode": "DIRECT_FALLBACK",
        "workerError": format!("{worker_error:#}"),
    });
    let capture = EvidenceCapture::begin(
        &context.shadow_home,
        &operation_id,
        &evidence_request,
        before.as_deref(),
        Some(&project),
    )?;
    let started = Instant::now();
    let mut command = ProcessCommand::new(env::current_exe()?);
    command
        .args(&request.arguments)
        .current_dir(&project)
        .env_clear()
        .env(DIRECT_ENV, "1")
        .env("TERMUX_SHADOW_OPERATION_ID", &operation_id)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    for (name, value) in base_environment(context) {
        command.env(name, value);
    }
    for (name, value) in &request.environment {
        if allowed_environment(name) {
            command.env(name, value);
        }
    }
    let output = command.output().context("start direct fallback executor")?;
    let after = fs::read(&registry_path).ok();
    let child_exit_code = output.status.code().unwrap_or(1);
    let json_contract_ok = !request.json || is_json_object(&output.stdout);
    let exit_code = if json_contract_ok {
        child_exit_code
    } else {
        child_exit_code.max(1)
    };
    let reference = capture.finish(
        &request.action.to_ascii_lowercase(),
        exit_code,
        &output.stdout,
        &output.stderr,
        elapsed_ms(started),
        after.as_deref(),
    )?;
    let mut worker = stopped_info(context);
    worker.status = "UNAVAILABLE".to_owned();
    worker.execution_mode = "DIRECT_FALLBACK".to_owned();
    let stdout = decorate_stdout(
        &output.stdout,
        request,
        &operation_id,
        &reference,
        &worker,
        child_exit_code,
    );
    print_captured(stdout.as_bytes(), &output.stderr, request.json)?;
    Ok(exit_code)
}

fn decorate_stdout(
    stdout: &[u8],
    request: &RpcRequest,
    operation_id: &str,
    evidence: &EvidenceRef,
    worker: &WorkerInfo,
    child_exit_code: i32,
) -> String {
    let verbose = request_is_verbose(request);
    let agent = request_is_agent(request);
    if request.json
        && let Ok(mut value) = serde_json::from_slice::<Value>(stdout)
    {
        normalize_public_ids(&mut value, &request.action);
        if verbose {
            normalize_verbose_result(&mut value, &request.action);
        } else if agent {
            value = agent_public_result(&value, &request.action);
        } else {
            value = compact_public_result(&value, &request.action);
        }
        if let Some(object) = value.as_object_mut() {
            object.insert("ok".to_owned(), Value::Bool(child_exit_code == 0));
            decorate_transport(
                object,
                &request.request_id,
                Some(operation_id),
                Some(evidence),
                worker,
                verbose,
            );
        }
        return serialize_public_json(&value, verbose);
    }
    if request.json {
        let mut value = json!({
            "protocolVersion": PROTOCOL_VERSION,
            "ok": false,
            "action": request.action.to_ascii_lowercase(),
            "phase": "workerExecutor",
            "code": "WORKER_CHILD_INVALID_JSON",
            "retryable": false,
            "message": "Worker executor did not return one JSON object",
            "diagnostics": [],
            "stateChanged": Value::Null,
            "childExitCode": child_exit_code,
            "stdoutBytes": stdout.len(),
        });
        if !verbose {
            value = if agent {
                agent_error_result(&value)
            } else {
                compact_error_result(&value)
            };
        }
        if let Some(object) = value.as_object_mut() {
            decorate_transport(
                object,
                &request.request_id,
                Some(operation_id),
                Some(evidence),
                worker,
                verbose,
            );
        }
        return serialize_public_json(&value, verbose);
    }
    let mut rendered = String::from_utf8_lossy(stdout).into_owned();
    if !request.json {
        if !rendered.ends_with('\n') {
            rendered.push('\n');
        }
        rendered.push_str(&format!(
            "evidence: {} ({})\nworker: {} status={} pid={}\n",
            evidence.id,
            evidence.sha256,
            worker.execution_mode,
            worker.status,
            worker
                .pid
                .map(|pid| pid.to_string())
                .as_deref()
                .unwrap_or("-")
        ));
    }
    rendered
}

fn request_is_verbose(request: &RpcRequest) -> bool {
    request
        .arguments
        .iter()
        .any(|argument| argument == "--verbose" || argument == "-v")
}

fn request_is_agent(request: &RpcRequest) -> bool {
    request
        .arguments
        .iter()
        .any(|argument| argument == "--agent")
        || (request.action == "DEV" && request.json && !request_is_verbose(request))
}

fn normalize_public_ids(value: &mut Value, action: &str) {
    let Some(object) = value.as_object_mut() else {
        return;
    };
    if let Some(operation_id) = object.remove("operationId") {
        let is_host_operation = matches!(action, "RUN" | "ROLLBACK")
            || object.get("phase").and_then(Value::as_str) == Some("runtimeHealth");
        if is_host_operation && !object.contains_key("hostOperationId") {
            object.insert("hostOperationId".to_owned(), operation_id);
        }
    }
    if let Some(launch) = object.get_mut("launch").and_then(Value::as_object_mut)
        && let Some(operation_id) = launch.remove("operationId")
    {
        launch
            .entry("hostOperationId".to_owned())
            .or_insert(operation_id);
    }
}

fn normalize_verbose_result(value: &mut Value, action: &str) {
    if !matches!(action, "DEV" | "DEPLOY") {
        return;
    }
    let Some(object) = value.as_object_mut() else {
        return;
    };
    let Some(launch) = object.remove("launch") else {
        return;
    };
    let Some(launch) = launch.as_object() else {
        return;
    };
    let mut proof = Map::new();
    copy_field(&mut proof, launch, "hostOperationId");
    copy_field(&mut proof, launch, "healthSemantics");
    copy_field(&mut proof, launch, "message");
    if !proof.is_empty() {
        object.insert("runtimeProof".to_owned(), Value::Object(proof));
    }
}

fn compact_public_result(value: &Value, action: &str) -> Value {
    if value.get("ok").and_then(Value::as_bool) == Some(false) || value.get("code").is_some() {
        return compact_error_result(value);
    }
    match action {
        "DEV" | "DEPLOY" => compact_dev_result(value),
        "BUILD" | "PUBLISH" | "UPGRADE" => compact_build_result(value, action),
        "DOCTOR" => compact_doctor_result(value),
        "RUN" | "ROLLBACK" => compact_launch_result(value),
        _ => value.clone(),
    }
}

fn agent_public_result(value: &Value, action: &str) -> Value {
    if value.get("ok").and_then(Value::as_bool) == Some(false) || value.get("code").is_some() {
        return agent_error_result(value);
    }
    match action {
        "DEV" | "DEPLOY" => agent_dev_result(value),
        _ => compact_public_result(value, action),
    }
}

fn agent_dev_result(value: &Value) -> Value {
    let Some(source) = value.as_object() else {
        return value.clone();
    };
    let mut output = Map::new();
    for key in [
        "ok",
        "action",
        "status",
        "pluginId",
        "sourceFingerprint",
        "durationMs",
        "stateChanged",
        "runtimeHealth",
        "diagnosticSummary",
    ] {
        copy_field(&mut output, source, key);
    }
    let status = source.get("status").and_then(Value::as_str).unwrap_or("");
    if let Some(version) = source.get("version").and_then(Value::as_object) {
        copy_field(&mut output, version, "versionCode");
        copy_field(&mut output, version, "versionName");
        copy_field(&mut output, version, "sha256");
        let generation_key = if matches!(
            status,
            "ACTIVE" | "NO_CHANGES" | "RUNTIME_VALIDATION_REQUIRED"
        ) {
            "activeGeneration"
        } else if status == "CANDIDATE_REGISTERED" {
            "candidateGeneration"
        } else {
            "generation"
        };
        copy_as(&mut output, version, "generation", generation_key);
    }
    if let Some(stages) = agent_stage_summary(source) {
        output.insert("stages".to_owned(), stages);
    }
    if let Some(launch) = source.get("launch").and_then(Value::as_object) {
        copy_field(&mut output, launch, "hostOperationId");
    }
    let mut context = Map::new();
    for key in [
        "project",
        "projectResolution",
        "dirtySinceActive",
        "nextVersionCode",
    ] {
        copy_field(&mut context, source, key);
    }
    let next_action = match status {
        "ACTIVE" | "NO_CHANGES" => "EDIT_AND_RERUN_DEV",
        "CANDIDATE_REGISTERED" | "RUNTIME_VALIDATION_REQUIRED" => "RERUN_DEV",
        _ => "RERUN_DEV",
    };
    context.insert(
        "nextAction".to_owned(),
        Value::String(next_action.to_owned()),
    );
    context.insert(
        "resumeCommand".to_owned(),
        Value::String("shadow-plugin dev".to_owned()),
    );
    output.insert("context".to_owned(), Value::Object(context));
    Value::Object(output)
}

fn agent_stage_summary(source: &Map<String, Value>) -> Option<Value> {
    let stages = source.get("stages")?.as_object()?;
    let timings = source.get("timings").and_then(Value::as_object);
    let mut output = Map::new();

    let mut doctor = Map::new();
    copy_as(&mut doctor, stages, "doctor", "status");
    if let Some(timings) = timings {
        copy_as(&mut doctor, timings, "doctorMs", "durationMs");
    }
    if !doctor.is_empty() {
        output.insert("doctor".to_owned(), Value::Object(doctor));
    }

    if let Some(build) = stages.get("build").and_then(Value::as_object) {
        let mut summary = Map::new();
        if let Some(status) = build.get("status").and_then(Value::as_str) {
            summary.insert(
                "status".to_owned(),
                Value::String(
                    match status {
                        "HIT" | "VALIDATED_REUSE" => "REUSED",
                        "MISS" | "FRESH" => "BUILT",
                        value => value,
                    }
                    .to_owned(),
                ),
            );
        }
        for key in ["daemon", "warningCount"] {
            copy_field(&mut summary, build, key);
        }
        if let Some(timings) = timings {
            copy_as(&mut summary, timings, "buildMs", "durationMs");
        } else {
            copy_field(&mut summary, build, "durationMs");
        }
        output.insert("build".to_owned(), Value::Object(summary));
    }

    for (stage, timing) in [("publish", "publishMs"), ("run", "runMs")] {
        let Some(status) = stages.get(stage) else {
            continue;
        };
        let mut summary = Map::new();
        summary.insert("status".to_owned(), status.clone());
        if let Some(timings) = timings {
            copy_as(&mut summary, timings, timing, "durationMs");
        }
        output.insert(stage.to_owned(), Value::Object(summary));
    }
    (!output.is_empty()).then_some(Value::Object(output))
}

fn agent_error_result(value: &Value) -> Value {
    let mut output = compact_error_result(value);
    let Some(object) = output.as_object_mut() else {
        return output;
    };
    let diagnostic_count = object
        .get("diagnostics")
        .and_then(Value::as_array)
        .map(Vec::len)
        .unwrap_or_default();
    object.insert(
        "diagnosticSummary".to_owned(),
        json!({
            "errors": diagnostic_count.max(1),
            "warnings": 0,
        }),
    );
    output
}

fn compact_doctor_result(value: &Value) -> Value {
    let Some(source) = value.as_object() else {
        return value.clone();
    };
    let mut output = Map::new();
    copy_field(&mut output, source, "ok");
    output.insert(
        "status".to_owned(),
        Value::String(
            if source.get("ok").and_then(Value::as_bool) == Some(true) {
                "PASS"
            } else {
                "FAILED"
            }
            .to_owned(),
        ),
    );
    copy_field(&mut output, source, "pluginId");
    copy_field(&mut output, source, "resourcePackageId");
    copy_as(&mut output, source, "pluginErrors", "errors");
    copy_as(&mut output, source, "pluginWarnings", "warnings");
    if let Some(package) = source.get("packageValidation").and_then(Value::as_object) {
        let mut package_summary = Map::new();
        for key in ["status", "cache", "sha256"] {
            copy_field(&mut package_summary, package, key);
        }
        if let Some(gradle) = package.get("gradle").and_then(Value::as_object) {
            copy_field(&mut package_summary, gradle, "daemon");
            copy_field(&mut package_summary, gradle, "durationMs");
            if let Some(warnings) = gradle.get("warnings").and_then(Value::as_array)
                && !warnings.is_empty()
            {
                package_summary.insert("buildWarningCount".to_owned(), warnings.len().into());
            }
        }
        if !package_summary.is_empty() {
            output.insert("package".to_owned(), Value::Object(package_summary));
        }
    }
    Value::Object(output)
}

fn compact_dev_result(value: &Value) -> Value {
    let Some(source) = value.as_object() else {
        return value.clone();
    };
    let mut output = Map::new();
    for key in ["ok", "status", "pluginId", "durationMs", "stateChanged"] {
        copy_field(&mut output, source, key);
    }
    let status = source.get("status").and_then(Value::as_str).unwrap_or("");
    let no_changes = status == "NO_CHANGES";
    if let Some(version) = source.get("version").and_then(Value::as_object) {
        if no_changes {
            copy_as(&mut output, version, "generation", "activeGeneration");
        } else {
            copy_field(&mut output, version, "versionCode");
            copy_field(&mut output, version, "versionName");
            copy_field(&mut output, version, "sha256");
            let generation_key = if matches!(status, "ACTIVE" | "RUNTIME_VALIDATION_REQUIRED") {
                "activeGeneration"
            } else if status == "CANDIDATE_REGISTERED" {
                "candidateGeneration"
            } else {
                "generation"
            };
            copy_as(&mut output, version, "generation", generation_key);
        }
    }
    if !no_changes {
        copy_field(&mut output, source, "nextVersionCode");
        if let Some(stages) = source.get("stages").and_then(Value::as_object) {
            let mut compact_stages = Map::new();
            for key in ["build", "publish", "run"] {
                copy_field(&mut compact_stages, stages, key);
            }
            if !compact_stages.is_empty() {
                output.insert("stages".to_owned(), Value::Object(compact_stages));
            }
        }
        if let Some(launch) = source.get("launch").and_then(Value::as_object) {
            copy_field(&mut output, launch, "hostOperationId");
        }
    }
    Value::Object(output)
}

fn compact_build_result(value: &Value, action: &str) -> Value {
    let Some(source) = value.as_object() else {
        return value.clone();
    };
    let mut output = Map::new();
    for key in ["ok", "status", "pluginId", "versionCode", "versionName"] {
        copy_field(&mut output, source, key);
    }
    let artifact = source
        .get("artifacts")
        .and_then(Value::as_array)
        .and_then(|artifacts| artifacts.first())
        .and_then(Value::as_object)
        .or_else(|| source.get("artifact").and_then(Value::as_object));
    if let Some(artifact) = artifact {
        copy_field(&mut output, artifact, "sha256");
    }
    let mut build = Map::new();
    copy_as(&mut build, source, "cache", "status");
    if let Some(gradle) = source.get("gradle").and_then(Value::as_object) {
        copy_field(&mut build, gradle, "daemon");
        copy_field(&mut build, gradle, "durationMs");
        if let Some(warnings) = gradle.get("warnings").and_then(Value::as_array)
            && !warnings.is_empty()
        {
            build.insert("warningCount".to_owned(), Value::from(warnings.len()));
        }
    }
    if !build.is_empty() {
        output.insert("build".to_owned(), Value::Object(build));
    }
    if source.get("stateChanged").is_some() {
        copy_field(&mut output, source, "stateChanged");
    } else {
        output.insert(
            "stateChanged".to_owned(),
            Value::Bool(
                action != "BUILD"
                    && source.get("status").and_then(Value::as_str) != Some("VALIDATED"),
            ),
        );
    }
    Value::Object(output)
}

fn compact_launch_result(value: &Value) -> Value {
    let Some(source) = value.as_object() else {
        return value.clone();
    };
    let mut output = Map::new();
    for key in [
        "ok",
        "status",
        "pluginId",
        "generation",
        "hostOperationId",
        "message",
    ] {
        copy_field(&mut output, source, key);
    }
    output.insert(
        "stateChanged".to_owned(),
        Value::Bool(source.get("status").and_then(Value::as_str) != Some("ALREADY_ACTIVE")),
    );
    Value::Object(output)
}

fn compact_error_result(value: &Value) -> Value {
    let Some(source) = value.as_object() else {
        return value.clone();
    };
    let mut output = Map::new();
    for key in [
        "ok",
        "action",
        "phase",
        "code",
        "retryable",
        "message",
        "diagnostics",
        "stateChanged",
        "logPath",
        "pluginId",
        "status",
        "generation",
        "hostOperationId",
        "context",
        "childExitCode",
        "stdoutBytes",
    ] {
        copy_field(&mut output, source, key);
    }
    Value::Object(output)
}

fn copy_field(target: &mut Map<String, Value>, source: &Map<String, Value>, key: &str) {
    copy_as(target, source, key, key);
}

fn copy_as(
    target: &mut Map<String, Value>,
    source: &Map<String, Value>,
    source_key: &str,
    target_key: &str,
) {
    if let Some(value) = source.get(source_key)
        && !value.is_null()
    {
        target.insert(target_key.to_owned(), value.clone());
    }
}

fn decorate_transport(
    object: &mut Map<String, Value>,
    request_id: &str,
    worker_operation_id: Option<&str>,
    evidence: Option<&EvidenceRef>,
    worker: &WorkerInfo,
    verbose: bool,
) {
    for key in [
        "protocolVersion",
        "requestId",
        "workerRequestId",
        "operationId",
        "workerOperationId",
        "worker",
        "evidence",
        "evidenceId",
    ] {
        object.remove(key);
    }
    if verbose {
        object.insert("protocolVersion".to_owned(), Value::from(PROTOCOL_VERSION));
        object.insert(
            "workerRequestId".to_owned(),
            Value::String(request_id.to_owned()),
        );
        if let Some(operation_id) = worker_operation_id {
            object.insert(
                "workerOperationId".to_owned(),
                Value::String(operation_id.to_owned()),
            );
        }
        object.insert(
            "worker".to_owned(),
            json!({
                "status": worker.status,
                "pid": worker.pid,
                "requestsServed": worker.requests_served,
                "gradleDaemon": worker.gradle_daemon,
                "executionMode": worker.execution_mode,
            }),
        );
        if let Some(evidence) = evidence {
            object.insert(
                "evidence".to_owned(),
                serde_json::to_value(evidence).unwrap_or(Value::Null),
            );
        }
    } else {
        if let Some(pid) = worker.pid {
            object.insert("workerPid".to_owned(), Value::from(pid));
        }
        if worker.status != "READY" {
            object.insert(
                "workerStatus".to_owned(),
                Value::String(worker.status.clone()),
            );
        }
        object.insert(
            "workerReused".to_owned(),
            Value::Bool(worker.execution_mode == "WORKER" && worker.requests_served > 1),
        );
        if worker.execution_mode != "WORKER" {
            object.insert(
                "executionMode".to_owned(),
                Value::String(worker.execution_mode.clone()),
            );
        }
        if let Some(evidence) = evidence {
            object.insert("evidenceId".to_owned(), Value::String(evidence.id.clone()));
        }
        object.remove("historyPath");
    }
}

fn serialize_public_json(value: &Value, verbose: bool) -> String {
    let serialized = if verbose {
        serde_json::to_string_pretty(value)
    } else {
        serde_json::to_string(value)
    };
    serialized
        .map(|text| format!("{text}\n"))
        .unwrap_or_else(|_| {
            "{\"ok\":false,\"code\":\"WORKER_JSON_SERIALIZATION_FAILED\"}\n".to_owned()
        })
}

fn is_json_object(stdout: &[u8]) -> bool {
    serde_json::from_slice::<Value>(stdout).is_ok_and(|value| value.is_object())
}

fn render_response(
    response: RpcResponse,
    json_output: bool,
    verbose: bool,
    agent: bool,
    action: &str,
) -> Result<i32> {
    if let Some(code) = response.error_code.as_deref() {
        if json_output {
            let mut value = json!({
                "ok": false,
                "action": action,
                "phase": "workerProtocol",
                "code": code,
                "retryable": matches!(
                    code,
                    "WORKER_UNAVAILABLE"
                        | "WORKER_PROTOCOL_MISMATCH"
                        | "WORKER_REQUEST_FAILED"
                ),
                "message": response.message.clone().unwrap_or_default(),
                "diagnostics": [],
                "stateChanged": false,
            });
            if agent && !verbose {
                value = agent_error_result(&value);
            }
            if let Some(object) = value.as_object_mut() {
                decorate_transport(
                    object,
                    &response.request_id,
                    response.operation_id.as_deref(),
                    response.evidence.as_ref(),
                    &response.worker,
                    verbose,
                );
            }
            print!("{}", serialize_public_json(&value, verbose));
        } else {
            eprintln!(
                "shadow-plugin: {code}: {}",
                response.message.unwrap_or_default()
            );
        }
        return Ok(response.exit_code.max(1));
    }
    print_captured(
        response.stdout.as_bytes(),
        response.stderr.as_bytes(),
        json_output,
    )?;
    Ok(response.exit_code)
}

fn print_captured(stdout: &[u8], stderr: &[u8], json: bool) -> Result<()> {
    if !json && !stderr.is_empty() {
        std::io::stderr().write_all(stderr)?;
        std::io::stderr().flush()?;
    }
    std::io::stdout().write_all(stdout)?;
    std::io::stdout().flush()?;
    Ok(())
}

fn build_request(
    context: &AppContext,
    action: &str,
    arguments: &[OsString],
    requested_id: Option<&str>,
) -> Result<RpcRequest> {
    let request_id = requested_id
        .map(str::to_owned)
        .unwrap_or_else(|| request_id("req"));
    if !safe_request_id(&request_id) {
        bail!("invalid Worker request id; use 8-96 path-safe characters");
    }
    let project = context
        .project()
        .ok()
        .map(|path| path.display().to_string());
    let arguments = arguments
        .iter()
        .skip(1)
        .map(|value| {
            value
                .to_str()
                .map(str::to_owned)
                .context("Worker arguments must be valid UTF-8")
        })
        .collect::<Result<Vec<_>>>()?;
    Ok(RpcRequest {
        protocol_version: PROTOCOL_VERSION,
        request_id,
        action: action.to_ascii_uppercase(),
        project,
        arguments,
        environment: request_environment(),
        json: context.json,
        client_version: env!("CARGO_PKG_VERSION").to_owned(),
    })
}

fn validate_request(context: &AppContext, request: &RpcRequest) -> Result<PathBuf> {
    let normalized_action = if request.action == "SHUTDOWN" {
        "STOP"
    } else {
        request.action.as_str()
    };
    if ![
        "BUILD", "PUBLISH", "UPGRADE", "DEV", "DEPLOY", "DOCTOR", "CLEAN", "RUN", "ROLLBACK",
        "STOP",
    ]
    .contains(&normalized_action)
    {
        bail!("Worker action is not allowed: {}", request.action);
    }
    for name in request.environment.keys() {
        if !allowed_environment(name) {
            bail!("Worker environment key is not allowed: {name}");
        }
    }
    let project = request
        .project
        .as_deref()
        .context("Worker request requires a resolved project")?;
    let requested_project =
        fs::canonicalize(project).with_context(|| format!("resolve project {project}"))?;
    let allowed_root = env::var_os("TERMUX_SHADOW_WORKER_TEST_ROOT")
        .map(PathBuf::from)
        .unwrap_or_else(|| context.termux_home.clone());
    let allowed_root = fs::canonicalize(&allowed_root).unwrap_or(allowed_root);
    let project = normalize_project_home_alias(requested_project, &allowed_root)?;
    if !project.join("shadow-plugin.properties").is_file() {
        bail!("Worker project is missing shadow-plugin.properties");
    }
    if request
        .arguments
        .iter()
        .any(|argument| argument == "__worker")
    {
        bail!("nested Worker invocation is forbidden");
    }
    let requested_command = command_from_arguments(&request.arguments)
        .context("Worker request does not contain a CLI command")?;
    let expected_command = normalized_action.to_ascii_lowercase();
    if requested_command != expected_command {
        bail!(
            "Worker action/command mismatch: action={} command={requested_command}",
            request.action
        );
    }
    Ok(project)
}

fn normalize_project_home_alias(project: PathBuf, allowed_root: &Path) -> Result<PathBuf> {
    if project.starts_with(allowed_root) {
        return Ok(project);
    }
    let request_home = project
        .ancestors()
        .find(|ancestor| is_termux_home(ancestor));
    if let Some(request_home) = request_home {
        let relative = project.strip_prefix(request_home)?;
        let mapped = fs::canonicalize(allowed_root.join(relative))
            .with_context(|| format!("resolve Worker home alias for {}", project.display()))?;
        if mapped.starts_with(allowed_root) && same_file_identity(&project, &mapped)? {
            return Ok(mapped);
        }
    }
    bail!(
        "Worker project escapes allowed Termux home: {}",
        project.display()
    )
}

fn same_file_identity(left: &Path, right: &Path) -> Result<bool> {
    let left = fs::metadata(left)?;
    let right = fs::metadata(right)?;
    Ok(left.dev() == right.dev() && left.ino() == right.ino())
}

fn command_from_arguments(arguments: &[String]) -> Option<String> {
    let mut index = 0usize;
    while index < arguments.len() {
        let argument = &arguments[index];
        if matches!(
            argument.as_str(),
            "--project" | "--template" | "--toolchain" | "--request-id"
        ) {
            index = index.saturating_add(2);
            continue;
        }
        if argument.starts_with('-') {
            index = index.saturating_add(1);
            continue;
        }
        let command = argument.to_ascii_lowercase();
        return Some(match command.as_str() {
            "retry" | "resume" => "dev".to_owned(),
            _ => command,
        });
    }
    None
}

fn connect_and_send(context: &AppContext, request: &RpcRequest) -> Result<RpcResponse> {
    connect_and_send_timeout(context, request, RESPONSE_TIMEOUT)
}

fn connect_and_send_timeout(
    context: &AppContext,
    request: &RpcRequest,
    response_timeout: Duration,
) -> Result<RpcResponse> {
    let socket = socket_path(context);
    let mut stream = UnixStream::connect(&socket)
        .with_context(|| format!("connect Worker socket {}", socket.display()))?;
    stream.set_read_timeout(Some(response_timeout))?;
    stream.set_write_timeout(Some(CONNECT_TIMEOUT))?;
    let bytes = serde_json::to_vec(request)?;
    if bytes.len() > MAX_FRAME_BYTES {
        bail!("Worker request exceeds protocol limit");
    }
    stream.write_all(&bytes)?;
    stream.shutdown(std::net::Shutdown::Write)?;
    let frame = read_frame(&mut stream)?;
    let response: RpcResponse = serde_json::from_slice(&frame).context("parse Worker response")?;
    if response.request_id != request.request_id {
        bail!("Worker response requestId mismatch");
    }
    if response.protocol_version != PROTOCOL_VERSION {
        bail!("WORKER_PROTOCOL_MISMATCH: Worker response protocol differs");
    }
    Ok(response)
}

fn connect_compatible(context: &AppContext, request: &RpcRequest) -> Result<RpcResponse> {
    let ping = RpcRequest {
        protocol_version: PROTOCOL_VERSION,
        request_id: request_id("handshake"),
        action: "PING".to_owned(),
        project: None,
        arguments: Vec::new(),
        environment: BTreeMap::new(),
        json: true,
        client_version: env!("CARGO_PKG_VERSION").to_owned(),
    };
    let handshake = connect_and_send_timeout(context, &ping, HANDSHAKE_TIMEOUT)?;
    let binary_sha256 = current_binary_sha256();
    if let Some(issue) = worker_compatibility_issue(
        &handshake.worker,
        env!("CARGO_PKG_VERSION"),
        binary_sha256.as_deref(),
    ) {
        bail!("WORKER_PROTOCOL_MISMATCH: {issue}");
    }
    connect_and_send(context, request)
}

fn current_binary_sha256() -> Option<String> {
    env::current_exe()
        .ok()
        .and_then(|executable| sha256_file(&executable).ok())
}

fn worker_compatibility_issue(
    worker: &WorkerInfo,
    client_version: &str,
    client_binary_sha256: Option<&str>,
) -> Option<String> {
    if worker.protocol_version != PROTOCOL_VERSION || worker.cli_version != client_version {
        return Some(format!(
            "client {client_version}/{} does not match Worker {}/{}",
            PROTOCOL_VERSION, worker.cli_version, worker.protocol_version
        ));
    }
    if let (Some(client_sha256), Some(worker_sha256)) =
        (client_binary_sha256, worker.binary_sha256.as_deref())
        && client_sha256 != worker_sha256
    {
        return Some(format!(
            "client and Worker binary SHA-256 differ (client={client_sha256}, worker={worker_sha256})"
        ));
    }
    None
}

fn read_frame(reader: &mut impl Read) -> Result<Vec<u8>> {
    let mut bytes = Vec::new();
    reader
        .take((MAX_FRAME_BYTES + 1) as u64)
        .read_to_end(&mut bytes)?;
    if bytes.len() > MAX_FRAME_BYTES {
        bail!("Worker protocol frame exceeds {} bytes", MAX_FRAME_BYTES);
    }
    if bytes.is_empty() {
        bail!("Worker protocol returned an empty frame");
    }
    Ok(bytes)
}

fn protocol_error(
    context: &AppContext,
    state: &RuntimeState,
    request_id: &str,
    code: &str,
    message: String,
) -> RpcResponse {
    RpcResponse {
        protocol_version: PROTOCOL_VERSION,
        request_id: request_id.to_owned(),
        request_hash: None,
        ok: false,
        exit_code: 1,
        stdout: String::new(),
        stderr: String::new(),
        operation_id: None,
        evidence: None,
        worker: running_info(context, state, "READY", "WORKER"),
        error_code: Some(code.to_owned()),
        message: Some(message),
    }
}

fn running_info(
    context: &AppContext,
    state: &RuntimeState,
    status: &str,
    execution_mode: &str,
) -> WorkerInfo {
    let expose_current = status == "BUSY";
    WorkerInfo {
        status: status.to_owned(),
        pid: Some(std::process::id()),
        protocol_version: PROTOCOL_VERSION,
        cli_version: env!("CARGO_PKG_VERSION").to_owned(),
        idle_timeout_seconds: state.idle_timeout.as_secs(),
        idle_remaining_seconds: state
            .idle_timeout
            .saturating_sub(state.last_activity.elapsed())
            .as_secs(),
        requests_served: state.requests_served,
        gradle_daemon: if state.gradle_daemon_pid.is_some() {
            "WARM".to_owned()
        } else {
            "COLD".to_owned()
        },
        gradle_daemon_pid: state.gradle_daemon_pid,
        gradle_daemon_managed: state.gradle_daemon_managed,
        started_at: Some(state.started_at),
        last_request_at: Some(state.last_request_at),
        socket: socket_path(context).display().to_string(),
        binary_sha256: state.binary_sha256.clone(),
        execution_mode: execution_mode.to_owned(),
        current_request_id: if expose_current {
            state.current_request_id.clone()
        } else {
            None
        },
        current_operation_id: if expose_current {
            state.current_operation_id.clone()
        } else {
            None
        },
        current_action: if expose_current {
            state.current_action.clone()
        } else {
            None
        },
    }
}

fn stopped_info(context: &AppContext) -> WorkerInfo {
    WorkerInfo {
        status: "STOPPED".to_owned(),
        pid: None,
        protocol_version: PROTOCOL_VERSION,
        cli_version: env!("CARGO_PKG_VERSION").to_owned(),
        idle_timeout_seconds: DEFAULT_IDLE_SECONDS,
        idle_remaining_seconds: 0,
        requests_served: 0,
        gradle_daemon: "UNKNOWN".to_owned(),
        gradle_daemon_pid: None,
        gradle_daemon_managed: false,
        started_at: None,
        last_request_at: None,
        socket: socket_path(context).display().to_string(),
        binary_sha256: None,
        execution_mode: "DIRECT".to_owned(),
        current_request_id: None,
        current_operation_id: None,
        current_action: None,
    }
}

fn write_state(context: &AppContext, state: &RuntimeState, status: &str) -> Result<()> {
    let info = running_info(context, state, status, "WORKER");
    let mut bytes = serde_json::to_vec_pretty(&info)?;
    bytes.push(b'\n');
    write_atomic(&state_path(context), &bytes)
}

fn cached_response(context: &AppContext, request_id: &str) -> Result<Option<RpcResponse>> {
    let path = request_cache_directory(context).join(format!("{request_id}.json"));
    if !path.is_file() {
        return Ok(None);
    }
    let bytes = fs::read(&path)?;
    Ok(Some(serde_json::from_slice(&bytes)?))
}

fn cache_response(context: &AppContext, response: &RpcResponse) -> Result<()> {
    let directory = request_cache_directory(context);
    create_private_dir(&directory)?;
    let path = directory.join(format!("{}.json", response.request_id));
    let mut bytes = serde_json::to_vec(response)?;
    bytes.push(b'\n');
    write_atomic(&path, &bytes)?;
    let mut entries = fs::read_dir(&directory)?
        .filter_map(Result::ok)
        .filter(|entry| entry.path().extension().and_then(OsStr::to_str) == Some("json"))
        .collect::<Vec<_>>();
    if entries.len() > MAX_CACHED_REQUESTS {
        entries.sort_by_key(|entry| entry.metadata().and_then(|meta| meta.modified()).ok());
        let remove = entries.len() - MAX_CACHED_REQUESTS;
        for entry in entries.into_iter().take(remove) {
            let _ = fs::remove_file(entry.path());
        }
    }
    Ok(())
}

fn acquire_lock(directory: &Path) -> Result<fs::File> {
    let path = directory.join(LOCK_NAME);
    for _ in 0..2 {
        match OpenOptions::new().write(true).create_new(true).open(&path) {
            Ok(mut lock) => {
                fs::set_permissions(&path, fs::Permissions::from_mode(0o600))?;
                writeln!(lock, "{}", std::process::id())?;
                lock.sync_all()?;
                return Ok(lock);
            }
            Err(error) if error.kind() == ErrorKind::AlreadyExists => {
                let live = fs::read_to_string(&path)
                    .ok()
                    .and_then(|value| value.trim().parse::<u32>().ok())
                    .is_some_and(process_alive);
                if live {
                    bail!("another shadow-plugin Worker is already running");
                }
                fs::remove_file(&path)?;
            }
            Err(error) => return Err(error).context("create Worker lock"),
        }
    }
    bail!("failed to acquire Worker lock")
}

fn peer_uid(stream: &UnixStream) -> Result<u32> {
    let mut credentials = libc::ucred {
        pid: 0,
        uid: 0,
        gid: 0,
    };
    let mut length = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
    let result = unsafe {
        libc::getsockopt(
            stream.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            (&mut credentials as *mut libc::ucred).cast(),
            &mut length,
        )
    };
    if result != 0 {
        return Err(std::io::Error::last_os_error()).context("read Worker peer credentials");
    }
    Ok(credentials.uid)
}

fn request_environment() -> BTreeMap<String, String> {
    env::vars()
        .filter(|(name, _)| allowed_environment(name))
        .collect()
}

fn base_environment(context: &AppContext) -> BTreeMap<OsString, OsString> {
    let mut values = BTreeMap::new();
    values.insert(
        OsString::from("HOME"),
        context.termux_home.as_os_str().to_owned(),
    );
    values.insert(
        OsString::from("TERMUX_HOME"),
        context.termux_home.as_os_str().to_owned(),
    );
    values.insert(
        OsString::from("PREFIX"),
        context.prefix.as_os_str().to_owned(),
    );
    values.insert(
        OsString::from("TERMUX_SHADOW_HOME"),
        context.shadow_home.as_os_str().to_owned(),
    );
    values.insert(
        OsString::from("PATH"),
        env::var_os("PATH").unwrap_or_else(|| {
            OsString::from(format!("{}/bin:/system/bin", context.prefix.display()))
        }),
    );
    for name in ["TMPDIR", "LANG", "LC_ALL", "LD_LIBRARY_PATH"] {
        if let Some(value) = env::var_os(name) {
            values.insert(OsString::from(name), value);
        }
    }
    values
}

fn allowed_environment(name: &str) -> bool {
    matches!(
        name,
        "ANDROID_HOME"
            | "ANDROID_SDK_ROOT"
            | "GRADLE_USER_HOME"
            | "JAVA_HOME"
            | "LANG"
            | "LC_ALL"
            | "LD_LIBRARY_PATH"
            | "PATH"
            | "PREFIX"
            | "TMPDIR"
            | "TERMUX_HOME"
            | "TERMUX_SHADOW_ANDROID_TOOLCHAIN"
            | "TERMUX_SHADOW_ANDROID_USER"
            | "TERMUX_SHADOW_CONTROL_ACTION"
            | "TERMUX_SHADOW_CONTROL_COMPONENT"
            | "TERMUX_SHADOW_CONFIGURATION_CACHE"
            | "TERMUX_SHADOW_HOME"
            | "TERMUX_SHADOW_OPERATION_ID"
            | "TERMUX_SHADOW_SIGNING_KEY_ID"
            | "TERMUX_SHADOW_SIGNING_KEY_PKCS8"
            | "TERMUX_SHADOW_TEMPLATE"
    )
}

fn sanitized_arguments(arguments: &[String]) -> Vec<String> {
    let mut sanitized = Vec::with_capacity(arguments.len());
    let mut redact_next = false;
    for argument in arguments {
        if redact_next {
            sanitized.push("[REDACTED]".to_owned());
            redact_next = false;
        } else if ["--token", "--password", "--secret"].contains(&argument.as_str()) {
            sanitized.push(argument.clone());
            redact_next = true;
        } else {
            sanitized.push(argument.clone());
        }
    }
    sanitized
}

fn action_may_use_gradle(action: &str) -> bool {
    matches!(
        action,
        "BUILD" | "PUBLISH" | "UPGRADE" | "DEV" | "DEPLOY" | "DOCTOR"
    )
}

fn find_gradle_daemon_pids(termux_home: &Path, version_marker: &str) -> BTreeSet<u32> {
    let current_uid = unsafe { libc::getuid() };
    let Ok(entries) = fs::read_dir("/proc") else {
        return BTreeSet::new();
    };
    let mut pids = BTreeSet::new();
    for entry in entries.filter_map(Result::ok) {
        let Ok(pid) = entry.file_name().to_string_lossy().parse::<u32>() else {
            continue;
        };
        let Ok(status) = fs::read_to_string(entry.path().join("status")) else {
            continue;
        };
        let uid = status
            .lines()
            .find_map(|line| line.strip_prefix("Uid:"))
            .and_then(|line| line.split_whitespace().next())
            .and_then(|value| value.parse::<u32>().ok());
        if uid != Some(current_uid) {
            continue;
        }
        let command = fs::read(entry.path().join("cmdline")).unwrap_or_default();
        let command = String::from_utf8_lossy(&command);
        if !command.contains("GradleDaemon") || !command.contains(version_marker) {
            continue;
        }
        let environment = fs::read(entry.path().join("environ")).unwrap_or_default();
        let expected_home = format!("HOME={}", termux_home.display());
        let owns_home = environment_has_entry(&environment, &expected_home);
        if owns_home {
            pids.insert(pid);
        }
    }
    pids
}

fn is_shadow_managed_gradle(pid: u32) -> bool {
    let environment =
        fs::read(Path::new("/proc").join(pid.to_string()).join("environ")).unwrap_or_default();
    environment_has_entry(&environment, &format!("{MANAGED_GRADLE_ENV}=1"))
}

fn environment_has_entry(environment: &[u8], expected: &str) -> bool {
    environment
        .split(|byte| *byte == 0)
        .any(|entry| entry == expected.as_bytes())
}

fn gradle_version_marker(project: &Path) -> Option<String> {
    let properties =
        fs::read_to_string(project.join("gradle/wrapper/gradle-wrapper.properties")).ok()?;
    let distribution = properties
        .lines()
        .find_map(|line| line.trim().strip_prefix("distributionUrl="))?;
    let name = distribution.rsplit('/').next()?;
    let version = name
        .strip_prefix("gradle-")?
        .split('-')
        .next()
        .filter(|value| !value.is_empty())?;
    Some(format!("GradleDaemon\0{version}"))
}

fn process_alive(pid: u32) -> bool {
    Path::new("/proc").join(pid.to_string()).is_dir()
}

fn stop_owned_gradle_daemon(pid: u32) {
    if pid == 0 {
        return;
    }
    unsafe {
        libc::kill(pid as libc::pid_t, libc::SIGTERM);
    }
    let deadline = Instant::now() + Duration::from_secs(2);
    while process_alive(pid) && Instant::now() < deadline {
        thread::sleep(Duration::from_millis(50));
    }
}

fn worker_directory(context: &AppContext) -> PathBuf {
    context.shadow_home.join(WORKER_DIR)
}

fn socket_path(context: &AppContext) -> PathBuf {
    worker_directory(context).join(SOCKET_NAME)
}

fn state_path(context: &AppContext) -> PathBuf {
    worker_directory(context).join(STATE_NAME)
}

fn request_cache_directory(context: &AppContext) -> PathBuf {
    worker_directory(context).join("requests")
}

fn create_private_dir(path: &Path) -> Result<()> {
    fs::create_dir_all(path).with_context(|| format!("create {}", path.display()))?;
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
    Ok(())
}

fn write_worker_log(context: &AppContext, message: &str) -> Result<()> {
    let path = context.shadow_home.join("logs/worker/worker.log");
    let parent = path.parent().context("Worker log has no parent")?;
    create_private_dir(parent)?;
    let mut file = OpenOptions::new().create(true).append(true).open(&path)?;
    fs::set_permissions(&path, fs::Permissions::from_mode(0o600))?;
    writeln!(file, "{} {}", now_millis(), message)?;
    Ok(())
}

fn safe_request_id(value: &str) -> bool {
    (8..=96).contains(&value.len())
        && value.chars().all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | '-')
        })
}

fn request_hash(request: &RpcRequest) -> Result<String> {
    let bytes = serde_json::to_vec(request)?;
    let mut digest = Sha256::new();
    digest.update(bytes);
    Ok(format!("{:x}", digest.finalize()))
}

fn worker_required() -> bool {
    env::var_os(REQUIRED_ENV).is_some_and(|value| value != "0" && value != "false")
}

fn request_id(prefix: &str) -> String {
    format!(
        "{prefix}-{}-{}-{}",
        std::process::id(),
        now_millis(),
        ID_SEQUENCE.fetch_add(1, Ordering::Relaxed)
    )
}

fn operation_id(action: &str) -> String {
    request_id(&format!("op-{}", action.to_ascii_lowercase()))
}

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .try_into()
        .unwrap_or(u64::MAX)
}

fn elapsed_ms(started: Instant) -> u64 {
    started.elapsed().as_millis().try_into().unwrap_or(u64::MAX)
}

#[cfg(test)]
mod tests {
    use super::{
        RpcRequest, WorkerInfo, allowed_environment, command_from_arguments, decorate_stdout,
        environment_has_entry, gradle_version_marker, is_json_object, safe_request_id,
        same_file_identity, validate_request, worker_compatibility_issue,
    };
    use crate::context::AppContext;
    use crate::evidence::EvidenceRef;
    use std::collections::BTreeMap;
    use std::fs;

    #[test]
    fn request_ids_are_bounded_and_path_safe() {
        assert!(safe_request_id("req-12345678"));
        assert!(!safe_request_id("../escape"));
        assert!(!safe_request_id("short"));
    }

    #[test]
    fn worker_environment_is_an_explicit_allowlist() {
        assert!(allowed_environment("JAVA_HOME"));
        assert!(allowed_environment("TERMUX_SHADOW_SIGNING_KEY_PKCS8"));
        assert!(!allowed_environment("OPENAI_API_KEY"));
    }

    #[test]
    fn managed_gradle_marker_requires_an_exact_environment_entry() {
        let environment =
            b"HOME=/data/user/0/com.termux/files/home\0TERMUX_SHADOW_MANAGED_GRADLE=1\0";
        assert!(environment_has_entry(
            environment,
            "TERMUX_SHADOW_MANAGED_GRADLE=1"
        ));
        assert!(!environment_has_entry(
            environment,
            "TERMUX_SHADOW_MANAGED_GRADLE=10"
        ));
    }

    #[test]
    fn worker_compatibility_covers_version_protocol_and_binary_identity() {
        let mut worker = test_worker();
        worker.cli_version = "0.7.1".into();
        worker.binary_sha256 = Some("current-sha".into());
        assert!(worker_compatibility_issue(&worker, "0.7.1", Some("current-sha")).is_none());
        assert!(
            worker_compatibility_issue(&worker, "0.7.0", Some("current-sha"))
                .unwrap()
                .contains("does not match")
        );
        assert!(
            worker_compatibility_issue(&worker, "0.7.1", Some("replacement-sha"))
                .unwrap()
                .contains("SHA-256 differ")
        );
    }

    #[test]
    fn rpc_action_must_match_the_real_cli_subcommand() {
        let arguments = vec![
            "--project".to_owned(),
            "/tmp/project".to_owned(),
            "--json".to_owned(),
            "deploy".to_owned(),
            "--run".to_owned(),
        ];
        assert_eq!(
            command_from_arguments(&arguments).as_deref(),
            Some("deploy")
        );
    }

    #[test]
    fn gradle_daemon_marker_is_derived_from_the_project_wrapper() {
        let root = tempfile::tempdir().unwrap();
        let wrapper = root.path().join("gradle/wrapper");
        fs::create_dir_all(&wrapper).unwrap();
        fs::write(
            wrapper.join("gradle-wrapper.properties"),
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.5.0-bin.zip\n",
        )
        .unwrap();
        assert_eq!(
            gradle_version_marker(root.path()).as_deref(),
            Some("GradleDaemon\09.5.0")
        );
    }

    #[test]
    fn worker_rejects_project_escape_and_nested_server() {
        let root = tempfile::tempdir().unwrap();
        let project = root.path().join("project");
        fs::create_dir_all(&project).unwrap();
        fs::write(
            project.join("shadow-plugin.properties"),
            "schemaVersion=1\n",
        )
        .unwrap();
        let context = AppContext::new(Some(project.clone()), None, None, true, false).unwrap();
        unsafe { std::env::set_var("TERMUX_SHADOW_WORKER_TEST_ROOT", root.path()) };
        let request = RpcRequest {
            protocol_version: 1,
            request_id: "req-12345678".into(),
            action: "BUILD".into(),
            project: Some(project.display().to_string()),
            arguments: vec!["build".into(), "__worker".into()],
            environment: BTreeMap::new(),
            json: true,
            client_version: "test".into(),
        };
        assert!(validate_request(&context, &request).is_err());
        unsafe { std::env::remove_var("TERMUX_SHADOW_WORKER_TEST_ROOT") };
    }

    #[test]
    fn home_alias_mapping_requires_the_same_inode() {
        let root = tempfile::tempdir().unwrap();
        let first = root.path().join("first");
        let alias = root.path().join("alias");
        let other = root.path().join("other");
        fs::write(&first, "same").unwrap();
        fs::hard_link(&first, &alias).unwrap();
        fs::write(&other, "same").unwrap();
        assert!(same_file_identity(&first, &alias).unwrap());
        assert!(!same_file_identity(&first, &other).unwrap());
    }

    #[test]
    fn invalid_child_stdout_is_wrapped_in_one_json_error() {
        let request = test_request();
        let output = decorate_stdout(
            b"partial Gradle text",
            &request,
            "op-test-json",
            &test_evidence(),
            &test_worker(),
            1,
        );
        assert!(is_json_object(output.as_bytes()));
        let value: serde_json::Value = serde_json::from_str(&output).unwrap();
        assert_eq!(value["ok"], false);
        assert_eq!(value["code"], "WORKER_CHILD_INVALID_JSON");
        assert_eq!(value["stdoutBytes"], 19);
        assert_eq!(value["evidenceId"], "op-test-json");
        assert!(value.get("evidence").is_none());
        assert!(value.get("requestId").is_none());
    }

    #[test]
    fn valid_child_json_is_decorated_without_trailing_text() {
        let request = test_request();
        let output = decorate_stdout(
            br#"{
                "ok":true,
                "status":"VALIDATED",
                "pluginId":"com.termux.shadow.notes",
                "versionCode":16,
                "versionName":"2.1.5",
                "artifacts":[{"path":"dist/notes.shadowpkg","sha256":"abcdef"}],
                "cache":"HIT",
                "gradle":null
            }"#,
            &request,
            "op-test-json",
            &test_evidence(),
            &test_worker(),
            0,
        );
        let value: serde_json::Value = serde_json::from_str(&output).unwrap();
        assert_eq!(value["ok"], true);
        assert_eq!(value["status"], "VALIDATED");
        assert_eq!(value["sha256"], "abcdef");
        assert_eq!(value["build"]["status"], "HIT");
        assert_eq!(value["evidenceId"], "op-test-json");
        assert_eq!(value["workerPid"], 123);
        assert!(value.get("worker").is_none());
        assert!(value.get("workerRequestId").is_none());
    }

    #[test]
    fn already_published_compaction_preserves_sha_and_idempotent_state() {
        let mut request = test_request();
        request.action = "PUBLISH".into();
        request.arguments = vec!["--json".into(), "publish".into()];
        let output = decorate_stdout(
            br#"{
                "ok":true,
                "action":"publish",
                "status":"ALREADY_PUBLISHED",
                "project":"/private/project",
                "pluginId":"com.termux.shadow.notes",
                "versionCode":16,
                "versionName":"2.1.5",
                "artifact":{"path":"dist/notes.shadowpkg","sha256":"abcdef"},
                "cache":"HIT",
                "gradle":null,
                "registrationConfirmed":true,
                "stateChanged":false
            }"#,
            &request,
            "op-test-json",
            &test_evidence(),
            &test_worker(),
            0,
        );
        let value: serde_json::Value = serde_json::from_str(&output).unwrap();
        assert_eq!(value["status"], "ALREADY_PUBLISHED");
        assert_eq!(value["sha256"], "abcdef");
        assert_eq!(value["build"]["status"], "HIT");
        assert_eq!(value["stateChanged"], false);
        assert!(value.get("project").is_none());
        assert!(value.get("artifact").is_none());
    }

    #[test]
    fn full_doctor_compacts_pass_checks_but_keeps_build_warning_count() {
        let mut request = test_request();
        request.action = "DOCTOR".into();
        request.arguments = vec!["--json".into(), "doctor".into(), "--full".into()];
        let output = decorate_stdout(
            br#"{
                "ok":true,
                "action":"doctor",
                "project":"/private/project",
                "pluginId":"com.termux.shadow.notes",
                "resourcePackageId":"0x7B",
                "pluginErrors":0,
                "pluginWarnings":0,
                "checks":[{"level":"OK","code":"CONFIG_VALID","message":"configuration is valid"}],
                "packageValidation":{"status":"PASS","cache":"MISS","artifact":"dist/notes.shadowpkg","sha256":"abcdef","gradle":{"durationMs":2200,"daemon":"REUSED","warnings":[{"code":"GRADLE_DEPRECATION","message":"deprecated"}]},"error":null}
            }"#,
            &request,
            "op-test-json",
            &test_evidence(),
            &test_worker(),
            0,
        );
        let value: serde_json::Value = serde_json::from_str(&output).unwrap();
        assert_eq!(value["status"], "PASS");
        assert_eq!(value["errors"], 0);
        assert_eq!(value["warnings"], 0);
        assert_eq!(value["package"]["daemon"], "REUSED");
        assert_eq!(value["package"]["buildWarningCount"], 1);
        assert!(value.get("checks").is_none());
        assert!(value.get("project").is_none());
    }

    #[test]
    fn no_changes_deploy_is_bounded_and_contains_only_decision_context() {
        let mut request = test_request();
        request.action = "DEPLOY".into();
        request.arguments = vec!["--json".into(), "deploy".into(), "--run".into()];
        let mut worker = test_worker();
        worker.requests_served = 8;
        let output = decorate_stdout(
            br#"{
                "ok":true,
                "action":"deploy",
                "status":"NO_CHANGES",
                "workerOperationId":"op-test-json",
                "pluginId":"com.termux.shadow.notes",
                "sourceFingerprint":"source-long-fingerprint",
                "toolchainFingerprint":"toolchain-long-fingerprint",
                "dirtySinceActive":false,
                "nextVersionCode":17,
                "version":{"versionCode":16,"versionName":"2.1.5","generation":"16-abcdef","sha256":"abcdef"},
                "stages":{"doctor":"PASS","build":{"status":"SKIPPED"},"publish":"ALREADY_REGISTERED","run":"SKIPPED"},
                "launch":null,
                "durationMs":87,
                "stateChanged":false,
                "historyPath":"/private/history.jsonl"
            }"#,
            &request,
            "op-test-json",
            &test_evidence(),
            &worker,
            0,
        );
        let value: serde_json::Value = serde_json::from_str(&output).unwrap();
        assert_eq!(value["status"], "NO_CHANGES");
        assert_eq!(value["activeGeneration"], "16-abcdef");
        assert_eq!(value["durationMs"], 87);
        assert_eq!(value["workerReused"], true);
        assert_eq!(value["evidenceId"], "op-test-json");
        for omitted in [
            "sourceFingerprint",
            "toolchainFingerprint",
            "nextVersionCode",
            "version",
            "stages",
            "historyPath",
            "workerOperationId",
        ] {
            assert!(value.get(omitted).is_none(), "unexpected {omitted}");
        }
        assert!(
            output.len() <= 320,
            "compact output was {} bytes",
            output.len()
        );
    }

    #[test]
    fn changed_deploy_stays_within_the_default_decision_budget() {
        let mut request = test_request();
        request.action = "DEPLOY".into();
        request.arguments = vec!["--json".into(), "deploy".into(), "--run".into()];
        let mut worker = test_worker();
        worker.requests_served = 8;
        let evidence = EvidenceRef {
            id: "op-deploy-30184-1784622716022-6".into(),
            sha256: "e".repeat(64),
            bytes: 48_320,
            complete: true,
        };
        let output = decorate_stdout(
            br#"{
                "ok":true,
                "action":"deploy",
                "status":"ACTIVE",
                "workerOperationId":"op-deploy-30184-1784622716022-6",
                "pluginId":"com.termux.shadow.notes",
                "sourceFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "toolchainFingerprint":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "dirtySinceActive":false,
                "nextVersionCode":17,
                "version":{"versionCode":16,"versionName":"2.1.5","generation":"16-a1b19dd0c17f3b61","sha256":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"},
                "stages":{"doctor":"PASS","build":{"status":"MISS","daemon":"REUSED","durationMs":2240,"warningCount":2},"publish":"REGISTERED","run":"HEALTHY"},
                "launch":{"ok":true,"action":"run","pluginId":"com.termux.shadow.notes","status":"HEALTHY","generation":"16-a1b19dd0c17f3b61","hostOperationId":"launch-1784622716022-a1b19dd0","healthSemantics":"FIRST_FRAME_AND_PROCESS_STABILITY","message":null},
                "durationMs":3920,
                "stateChanged":true,
                "historyPath":"/data/data/com.termux/files/home/.termux-shadow/history/com.termux.shadow.notes.jsonl"
            }"#,
            &request,
            "op-deploy-30184-1784622716022-6",
            &evidence,
            &worker,
            0,
        );
        let value: serde_json::Value = serde_json::from_str(&output).unwrap();
        assert_eq!(value["status"], "ACTIVE");
        assert_eq!(value["versionCode"], 16);
        assert_eq!(value["stages"]["build"]["daemon"], "REUSED");
        assert_eq!(value["hostOperationId"], "launch-1784622716022-a1b19dd0");
        assert_eq!(value["evidenceId"], "op-deploy-30184-1784622716022-6");
        assert_eq!(output.matches("\"pluginId\"").count(), 1);
        for omitted in [
            "sourceFingerprint",
            "toolchainFingerprint",
            "historyPath",
            "launch",
            "workerOperationId",
        ] {
            assert!(value.get(omitted).is_none(), "unexpected {omitted}");
        }
        assert!(
            output.len() <= 900,
            "changed deploy output was {} bytes",
            output.len()
        );
    }

    #[test]
    fn agent_deploy_contains_only_the_reusable_development_decision() {
        let mut request = test_request();
        request.action = "DEPLOY".into();
        request.arguments = vec!["--agent".into(), "deploy".into(), "--run".into()];
        let mut worker = test_worker();
        worker.requests_served = 8;
        let output = decorate_stdout(
            br#"{
                "ok":true,
                "action":"deploy",
                "status":"ACTIVE",
                "workerOperationId":"op-child",
                "pluginId":"com.termux.shadow.notes",
                "sourceFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "toolchainFingerprint":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "dirtySinceActive":false,
                "nextVersionCode":17,
                "version":{"versionCode":16,"versionName":"2.1.5","generation":"16-a1b19dd0c17f3b61","sha256":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"},
                "stages":{"doctor":"PASS","build":{"status":"MISS","daemon":"REUSED","durationMs":2240,"gradleDurationMs":2100,"warningCount":2},"publish":"REGISTERED","run":"HEALTHY"},
                "timings":{"doctorMs":24,"buildMs":2240,"publishMs":311,"runMs":1320},
                "diagnosticSummary":{"errors":0,"warnings":2},
                "runtimeHealth":"HEALTHY",
                "launch":{"ok":true,"action":"run","pluginId":"com.termux.shadow.notes","status":"HEALTHY","generation":"16-a1b19dd0c17f3b61","hostOperationId":"launch-1784622716022-a1b19dd0","healthSemantics":"FIRST_FRAME_AND_PROCESS_STABILITY","message":null},
                "durationMs":3920,
                "stateChanged":true,
                "historyPath":"/private/history.jsonl"
            }"#,
            &request,
            "op-agent-deploy",
            &test_evidence(),
            &worker,
            0,
        );
        let value: serde_json::Value = serde_json::from_str(&output).unwrap();
        assert_eq!(value["versionCode"], 16);
        assert_eq!(value["versionName"], "2.1.5");
        assert_eq!(value["sourceFingerprint"], "a".repeat(64));
        assert_eq!(value["stages"]["doctor"]["durationMs"], 24);
        assert_eq!(value["stages"]["build"]["durationMs"], 2240);
        assert_eq!(value["stages"]["publish"]["durationMs"], 311);
        assert_eq!(value["stages"]["run"]["durationMs"], 1320);
        assert_eq!(value["runtimeHealth"], "HEALTHY");
        assert_eq!(value["diagnosticSummary"]["warnings"], 2);
        assert_eq!(value["evidenceId"], "op-test-json");
        for omitted in [
            "toolchainFingerprint",
            "nextVersionCode",
            "historyPath",
            "launch",
            "workerOperationId",
        ] {
            assert!(value.get(omitted).is_none(), "unexpected {omitted}");
        }
        assert!(
            output.len() <= 1_100,
            "agent deploy output was {} bytes",
            output.len()
        );
    }

    #[test]
    fn agent_no_changes_still_identifies_the_current_committed_version() {
        let mut request = test_request();
        request.action = "DEPLOY".into();
        request.arguments = vec!["--agent".into(), "deploy".into(), "--run".into()];
        let mut worker = test_worker();
        worker.requests_served = 8;
        let output = decorate_stdout(
            br#"{
                "ok":true,
                "action":"deploy",
                "status":"NO_CHANGES",
                "workerOperationId":"op-child",
                "pluginId":"com.termux.shadow.notes",
                "sourceFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "toolchainFingerprint":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "dirtySinceActive":false,
                "nextVersionCode":17,
                "version":{"versionCode":16,"versionName":"2.1.5","generation":"16-a1b19dd0c17f3b61","sha256":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"},
                "stages":{"doctor":"PASS","build":{"status":"SKIPPED"},"publish":"ALREADY_REGISTERED","run":"SKIPPED"},
                "timings":{"doctorMs":18},
                "diagnosticSummary":{"errors":0,"warnings":0},
                "runtimeHealth":"HEALTHY",
                "launch":null,
                "durationMs":87,
                "stateChanged":false,
                "historyPath":"/private/history.jsonl"
            }"#,
            &request,
            "op-agent-no-changes",
            &test_evidence(),
            &worker,
            0,
        );
        let value: serde_json::Value = serde_json::from_str(&output).unwrap();
        assert_eq!(value["status"], "NO_CHANGES");
        assert_eq!(value["versionCode"], 16);
        assert_eq!(value["versionName"], "2.1.5");
        assert_eq!(value["activeGeneration"], "16-a1b19dd0c17f3b61");
        assert_eq!(value["sourceFingerprint"], "a".repeat(64));
        assert_eq!(value["runtimeHealth"], "HEALTHY");
        assert_eq!(value["stages"]["doctor"]["durationMs"], 18);
        assert_eq!(value["evidenceId"], "op-test-json");
        assert!(
            output.len() <= 900,
            "agent NO_CHANGES output was {} bytes",
            output.len()
        );
    }

    #[test]
    fn dev_json_is_the_agent_contract_without_an_extra_flag() {
        let mut request = test_request();
        request.action = "DEV".into();
        request.arguments = vec!["--json".into(), "dev".into()];
        let output = decorate_stdout(
            br#"{
                "ok":true,
                "action":"dev",
                "status":"NO_CHANGES",
                "project":"/home/termux-shadow-notes",
                "projectResolution":"CURRENT_DIRECTORY",
                "pluginId":"com.termux.shadow.notes",
                "sourceFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "dirtySinceActive":false,
                "nextVersionCode":17,
                "version":{"versionCode":16,"versionName":"2.1.5","generation":"16-a1b19dd0c17f3b61","sha256":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"},
                "stages":{"doctor":"PASS","build":{"status":"SKIPPED"},"publish":"ALREADY_REGISTERED","run":"SKIPPED"},
                "timings":{"doctorMs":18},
                "diagnosticSummary":{"errors":0,"warnings":0},
                "runtimeHealth":"HEALTHY",
                "durationMs":87,
                "stateChanged":false
            }"#,
            &request,
            "op-agent-dev",
            &test_evidence(),
            &test_worker(),
            0,
        );
        let value: serde_json::Value = serde_json::from_str(&output).unwrap();
        assert_eq!(value["status"], "NO_CHANGES");
        assert_eq!(value["context"]["project"], "/home/termux-shadow-notes");
        assert_eq!(value["context"]["projectResolution"], "CURRENT_DIRECTORY");
        assert_eq!(value["context"]["nextVersionCode"], 17);
        assert_eq!(value["context"]["nextAction"], "EDIT_AND_RERUN_DEV");
        assert_eq!(value["context"]["resumeCommand"], "shadow-plugin dev");
        assert_eq!(value["workerPid"], 123);
    }

    #[test]
    fn agent_failure_keeps_actionable_diagnostics_and_adds_a_count() {
        let mut request = test_request();
        request.action = "DEPLOY".into();
        request.arguments = vec!["--agent".into(), "deploy".into(), "--run".into()];
        let output = decorate_stdout(
            br#"{
                "ok":false,
                "action":"deploy",
                "phase":"compileJava",
                "code":"JAVA_COMPILE_ERROR",
                "retryable":false,
                "message":"Java compilation failed",
                "diagnostics":[{"file":"NotesActivity.java","line":86,"column":36,"message":"cannot find symbol: missingMessage"}],
                "stateChanged":false,
                "logPath":"build/logs/last-build.log",
                "context":{"project":"/home/termux-shadow-notes","currentHealthy":{"versionCode":16,"versionName":"2.1.5","generation":"16-a1b19dd0c17f3b61","sha256":"abcdef"},"nextVersionCode":17,"activeChanged":false,"nextAction":"FIX_AND_RERUN_DEV","resumeCommand":"shadow-plugin dev"}
            }"#,
            &request,
            "op-agent-failed",
            &test_evidence(),
            &test_worker(),
            1,
        );
        let value: serde_json::Value = serde_json::from_str(&output).unwrap();
        assert!(is_json_object(output.as_bytes()));
        assert_eq!(value["diagnosticSummary"]["errors"], 1);
        assert_eq!(value["diagnostics"][0]["line"], 86);
        assert_eq!(value["stateChanged"], false);
        assert_eq!(value["context"]["currentHealthy"]["versionCode"], 16);
        assert_eq!(value["context"]["nextVersionCode"], 17);
        assert_eq!(value["context"]["activeChanged"], false);
        assert_eq!(value["context"]["resumeCommand"], "shadow-plugin dev");
        assert_eq!(value["evidenceId"], "op-test-json");
    }

    #[test]
    fn verbose_deploy_uses_unambiguous_ids_without_repeating_launch_identity() {
        let mut request = test_request();
        request.action = "DEPLOY".into();
        request.arguments = vec![
            "--json".into(),
            "--verbose".into(),
            "deploy".into(),
            "--run".into(),
        ];
        let output = decorate_stdout(
            br#"{
                "ok":true,
                "action":"deploy",
                "status":"ACTIVE",
                "workerOperationId":"op-child",
                "pluginId":"com.termux.shadow.notes",
                "sourceFingerprint":"source",
                "toolchainFingerprint":"toolchain",
                "dirtySinceActive":false,
                "nextVersionCode":17,
                "version":{"versionCode":16,"versionName":"2.1.5","generation":"16-abcdef","sha256":"abcdef"},
                "stages":{"doctor":"PASS","build":{"status":"MISS","daemon":"REUSED","durationMs":2200},"publish":"REGISTERED","run":"HEALTHY"},
                "launch":{"ok":true,"action":"run","pluginId":"com.termux.shadow.notes","status":"HEALTHY","generation":"16-abcdef","hostOperationId":"host-launch-1","healthSemantics":"FIRST_FRAME_AND_PROCESS_STABILITY","message":null},
                "durationMs":2500,
                "stateChanged":true,
                "historyPath":"/private/history.jsonl"
            }"#,
            &request,
            "op-test-json",
            &test_evidence(),
            &test_worker(),
            0,
        );
        let value: serde_json::Value = serde_json::from_str(&output).unwrap();
        assert_eq!(value["workerRequestId"], "req-test-json");
        assert_eq!(value["workerOperationId"], "op-test-json");
        assert_eq!(value["runtimeProof"]["hostOperationId"], "host-launch-1");
        assert_eq!(value["evidence"]["evidenceId"], "op-test-json");
        assert_eq!(value["worker"]["executionMode"], "WORKER");
        assert!(value.get("launch").is_none());
        assert!(value.get("requestId").is_none());
        assert!(value.get("operationId").is_none());
    }

    #[test]
    fn compact_runtime_failure_keeps_the_java_crash_diagnostic() {
        let mut request = test_request();
        request.action = "DEPLOY".into();
        request.arguments = vec!["--json".into(), "deploy".into(), "--run".into()];
        let output = decorate_stdout(
            br#"{
                "ok":false,
                "action":"deploy",
                "phase":"runtimeHealth",
                "code":"ACTIVATION_FAILED",
                "retryable":true,
                "message":"Activity crashed",
                "diagnostics":[{"kind":"RUNTIME_CRASH","activity":"com.termux.shadow.notes.NotesActivity","errorType":"java.lang.IllegalStateException","message":"boom"}],
                "stateChanged":true,
                "logPath":"reports/runtime-crash/host-launch-1.json",
                "pluginId":"com.termux.shadow.notes",
                "generation":"16-abcdef",
                "hostOperationId":"host-launch-1"
            }"#,
            &request,
            "op-test-json",
            &test_evidence(),
            &test_worker(),
            1,
        );
        let value: serde_json::Value = serde_json::from_str(&output).unwrap();
        assert_eq!(value["diagnostics"][0]["kind"], "RUNTIME_CRASH");
        assert_eq!(value["hostOperationId"], "host-launch-1");
        assert_eq!(value["evidenceId"], "op-test-json");
        assert_eq!(value["logPath"], "reports/runtime-crash/host-launch-1.json");
    }

    #[test]
    fn stop_response_reports_the_worker_as_stopping_instead_of_ready() {
        let mut request = test_request();
        request.action = "SHUTDOWN".into();
        request.arguments = vec!["--json".into(), "stop".into()];
        let mut worker = test_worker();
        worker.status = "STOPPING".into();
        let output = decorate_stdout(
            br#"{"ok":true,"action":"stop","status":"OK","stateChanged":false}"#,
            &request,
            "op-stop-test",
            &test_evidence(),
            &worker,
            0,
        );
        let value: serde_json::Value = serde_json::from_str(&output).unwrap();
        assert_eq!(value["workerStatus"], "STOPPING");
        assert_ne!(value["workerStatus"], "READY");
    }

    fn test_request() -> RpcRequest {
        RpcRequest {
            protocol_version: 1,
            request_id: "req-test-json".into(),
            action: "BUILD".into(),
            project: Some("/tmp/project".into()),
            arguments: vec!["build".into(), "--json".into()],
            environment: BTreeMap::new(),
            json: true,
            client_version: "test".into(),
        }
    }

    fn test_evidence() -> EvidenceRef {
        EvidenceRef {
            id: "op-test-json".into(),
            sha256: "a".repeat(64),
            bytes: 42,
            complete: true,
        }
    }

    fn test_worker() -> WorkerInfo {
        WorkerInfo {
            status: "READY".into(),
            pid: Some(123),
            protocol_version: 1,
            cli_version: "test".into(),
            idle_timeout_seconds: 3600,
            idle_remaining_seconds: 3599,
            requests_served: 1,
            gradle_daemon: "WARM".into(),
            gradle_daemon_pid: Some(456),
            gradle_daemon_managed: true,
            started_at: Some(1),
            last_request_at: Some(2),
            socket: "/tmp/worker.sock".into(),
            binary_sha256: None,
            execution_mode: "WORKER".into(),
            current_request_id: None,
            current_operation_id: None,
            current_action: None,
        }
    }
}
