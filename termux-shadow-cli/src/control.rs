use std::fs;
use std::path::PathBuf;
use std::process::Command;
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, bail};
use serde::Serialize;
use serde_json::Value;

use crate::cli::{DeleteArgs, LaunchArgs};
use crate::config::PluginConfig;
use crate::context::AppContext;
use crate::errors::{Diagnostic, RuntimeActivationFailure};
use crate::runtime_crash::RuntimeCrash;
use crate::status::{read_launch_report, read_registry};

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ControlResult<'a> {
    ok: bool,
    method: &'a str,
    plugin_id: Option<&'a str>,
    status: &'a str,
    message: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct LaunchOutcome {
    pub ok: bool,
    pub action: &'static str,
    pub plugin_id: String,
    pub status: &'static str,
    pub generation: Option<String>,
    #[serde(rename = "hostOperationId")]
    pub operation_id: Option<String>,
    pub health_semantics: Option<&'static str>,
    pub smoke_requested: bool,
    pub message: Option<String>,
}

#[derive(Debug, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct HostResponse {
    request_id: String,
    method: Option<String>,
    plugin_id: Option<String>,
    status: String,
    message: String,
}

pub fn run_launch(context: &AppContext, args: LaunchArgs, rollback: bool) -> Result<()> {
    execute_launch(context, args, rollback, true).map(|_| ())
}

pub(crate) fn execute_launch(
    context: &AppContext,
    args: LaunchArgs,
    rollback: bool,
    emit: bool,
) -> Result<LaunchOutcome> {
    let smoke_spec = load_smoke_spec(context, &args)?;
    let plugin_id = resolve_plugin_id(context, args.plugin_id)?;
    // A smoke request is an explicit request to exercise the live UI.  Reusing the
    // registry proof would report success without executing a single smoke step.
    if !rollback && !args.force && smoke_spec.is_none() {
        let registry = read_registry(&context.shadow_home)?;
        if let Some(plugin) = registry
            .plugins
            .iter()
            .find(|plugin| plugin.plugin_id == plugin_id)
            && plugin.candidate_generation.is_none()
            && plugin.activating_generation.is_none()
            && let Some(active_generation) = plugin.active_generation.as_deref()
            && let Some(active) = plugin
                .versions
                .iter()
                .find(|version| version.generation == active_generation)
            && active.state == "HEALTHY"
            && active.runtime_health_protocol_version >= 1
            && active.runtime_stable_at > 0
            && active.last_healthy_process_pid > 0
        {
            crate::runtime_artifacts::record_healthy(context, &plugin_id, active_generation, None)?;
            let outcome = LaunchOutcome {
                ok: true,
                action: "run",
                plugin_id,
                status: "ALREADY_ACTIVE",
                generation: Some(active_generation.to_owned()),
                operation_id: None,
                health_semantics: Some("FIRST_FRAME_AND_PROCESS_STABILITY"),
                smoke_requested: smoke_spec.is_some(),
                message: None,
            };
            if emit {
                emit_launch(context, &outcome)?;
            }
            return Ok(outcome);
        }
    }
    let previous_operation =
        read_launch_report(&context.shadow_home, &plugin_id)?.map(|report| report.operation_id);
    let method = if rollback { "rollback" } else { "run" };
    let message = call(context, method, Some(&plugin_id), smoke_spec.as_deref())?;
    if args.no_wait {
        let outcome = LaunchOutcome {
            ok: true,
            action: method,
            plugin_id,
            status: "ACCEPTED",
            generation: None,
            operation_id: None,
            health_semantics: None,
            smoke_requested: smoke_spec.is_some(),
            message: Some(message),
        };
        if emit {
            emit_launch(context, &outcome)?;
        }
        return Ok(outcome);
    }

    let started = Instant::now();
    let timeout = Duration::from_secs(args.timeout);
    let mut last_status = None;
    let mut last_generation = None;
    let mut last_operation = None;
    loop {
        if let Some(report) = read_launch_report(&context.shadow_home, &plugin_id)?
            && previous_operation.as_deref() != Some(report.operation_id.as_str())
        {
            last_status = Some(report.status.clone());
            last_generation = Some(report.generation.clone());
            last_operation = Some(report.operation_id.clone());
            match report.status.as_str() {
                "HEALTHY" => {
                    crate::runtime_artifacts::record_healthy(
                        context,
                        &plugin_id,
                        &report.generation,
                        Some(&report.operation_id),
                    )?;
                    let outcome = LaunchOutcome {
                        ok: true,
                        action: method,
                        plugin_id,
                        status: "HEALTHY",
                        generation: Some(report.generation),
                        operation_id: Some(report.operation_id),
                        health_semantics: Some(if smoke_spec.is_some() {
                            "FIRST_FRAME_UI_SMOKE_AND_PROCESS_STABILITY"
                        } else {
                            "FIRST_FRAME_AND_PROCESS_STABILITY"
                        }),
                        smoke_requested: smoke_spec.is_some(),
                        message: None,
                    };
                    if emit {
                        emit_launch(context, &outcome)?;
                    }
                    return Ok(outcome);
                }
                "FAILED" | "ROLLED_BACK" => {
                    let message = report
                        .error
                        .unwrap_or_else(|| "runtime health check failed".to_owned());
                    let message = record_failure_marker(
                        context,
                        &plugin_id,
                        Some(&report.generation),
                        Some(&report.operation_id),
                        &report.status,
                        message,
                    );
                    return Err(runtime_failure(
                        context,
                        method,
                        plugin_id,
                        report.status,
                        Some(report.generation),
                        Some(report.operation_id),
                        message,
                    )
                    .into());
                }
                _ => {}
            }
        }
        if started.elapsed() >= timeout {
            let status = last_status.unwrap_or_else(|| "WAITING_FOR_REPORT".to_owned());
            let message = format!(
                "runtime health confirmation timed out after {}s",
                timeout.as_secs()
            );
            let message = record_failure_marker(
                context,
                &plugin_id,
                last_generation.as_deref(),
                last_operation.as_deref(),
                &status,
                message,
            );
            return Err(runtime_failure(
                context,
                method,
                plugin_id,
                status,
                last_generation,
                last_operation,
                message,
            )
            .into());
        }
        thread::sleep(Duration::from_millis(250));
    }
}

fn record_failure_marker(
    context: &AppContext,
    plugin_id: &str,
    generation: Option<&str>,
    operation_id: Option<&str>,
    status: &str,
    message: String,
) -> String {
    match crate::runtime_artifacts::record_failure(
        context,
        plugin_id,
        generation,
        operation_id,
        status,
        &message,
    ) {
        Ok(()) => message,
        Err(error) => format!("{message}; artifact safety metadata update failed: {error:#}"),
    }
}

fn runtime_failure(
    context: &AppContext,
    action: &'static str,
    plugin_id: String,
    status: String,
    generation: Option<String>,
    operation_id: Option<String>,
    message: String,
) -> RuntimeActivationFailure {
    let crash = operation_id.as_deref().and_then(|operation_id| {
        crate::runtime_crash::find(
            &context.shadow_home,
            operation_id,
            Some(&plugin_id),
            generation.as_deref(),
        )
        .ok()
        .flatten()
    });
    let diagnostics = crash
        .as_ref()
        .map(runtime_crash_diagnostic)
        .into_iter()
        .collect();
    let log_path = crash
        .as_ref()
        .map(|crash| crash.relative_path(&context.shadow_home));
    RuntimeActivationFailure {
        action,
        plugin_id,
        status,
        generation,
        operation_id,
        message,
        diagnostics,
        log_path,
        state_changed: true,
    }
}

fn runtime_crash_diagnostic(crash: &RuntimeCrash) -> Diagnostic {
    Diagnostic {
        kind: Some("RUNTIME_CRASH".to_owned()),
        file: None,
        line: None,
        column: None,
        activity: crash.activity().map(str::to_owned),
        error_type: crash.error_type().map(str::to_owned),
        message: crash.diagnostic_message(),
    }
}

fn emit_launch(context: &AppContext, outcome: &LaunchOutcome) -> Result<()> {
    if context.json {
        println!("{}", serde_json::to_string_pretty(outcome)?);
    } else {
        println!(
            "{}: {}  {}",
            outcome.action, outcome.status, outcome.plugin_id
        );
        if let Some(generation) = &outcome.generation {
            println!("  generation={generation}");
        }
        if let Some(operation_id) = &outcome.operation_id {
            println!("  hostOperationId={operation_id}");
        }
        if let Some(message) = &outcome.message {
            println!("  {message}");
        }
    }
    Ok(())
}

pub fn run_mutation(
    context: &AppContext,
    method: &'static str,
    requested_plugin_id: Option<String>,
) -> Result<()> {
    let plugin_id = resolve_plugin_id(context, requested_plugin_id)?;
    let message = call(context, method, Some(&plugin_id), None)?;
    let registry = read_registry(&context.shadow_home)?;
    let plugin = registry
        .plugins
        .iter()
        .find(|plugin| plugin.plugin_id == plugin_id)
        .with_context(|| {
            format!("Host completed {method} but {plugin_id} is absent from registry")
        })?;
    match method {
        "disable" if plugin.enabled => bail!("Host returned success but plugin remains enabled"),
        "enable" if !plugin.enabled => bail!("Host returned success but plugin remains disabled"),
        _ => {}
    }
    emit_control(context, method, Some(&plugin_id), "OK", message)
}

pub fn run_delete(context: &AppContext, args: DeleteArgs) -> Result<()> {
    if !args.yes {
        bail!(
            "delete removes every managed version; repeat with --yes (audit logs and archives remain preserved)"
        );
    }
    let plugin_id = resolve_plugin_id(context, args.plugin_id)?;
    let message = call(context, "delete", Some(&plugin_id), None)?;
    let registry = read_registry(&context.shadow_home)?;
    if registry
        .plugins
        .iter()
        .any(|plugin| plugin.plugin_id == plugin_id)
    {
        bail!("Host returned success but {plugin_id} remains registered");
    }
    emit_control(context, "delete", Some(&plugin_id), "OK", message)
}

pub fn run_refresh(context: &AppContext) -> Result<()> {
    let message = call(context, "refresh", None, None)?;
    emit_control(context, "refresh", None, "OK", message)
}

pub fn try_refresh(context: &AppContext) -> Result<()> {
    call(context, "refresh", None, None).map(|_| ())
}

pub fn ping(context: &AppContext) -> Result<String> {
    call(context, "ping", None, None)
}

pub fn ensure_build_worker(context: &AppContext) -> Result<String> {
    call(context, "ensure-worker", None, None)
}

pub fn stop_build_worker(context: &AppContext) -> Result<String> {
    call(context, "stop-worker", None, None)
}

fn resolve_plugin_id(context: &AppContext, requested: Option<String>) -> Result<String> {
    if let Some(value) = requested {
        if value.trim().is_empty() {
            bail!("pluginId cannot be empty");
        }
        return Ok(value);
    }
    let project = context.project().context(
        "pluginId was omitted and no current project could provide it; pass pluginId explicitly",
    )?;
    Ok(PluginConfig::load(&project.join("shadow-plugin.properties"))?.plugin_id)
}

fn load_smoke_spec(context: &AppContext, args: &LaunchArgs) -> Result<Option<String>> {
    if !args.smoke {
        return Ok(None);
    }
    let path = args.smoke_file.clone().unwrap_or_else(|| {
        context
            .project_if_present()
            .map(|project| project.join("shadow-smoke.json"))
            .unwrap_or_else(|| PathBuf::from("shadow-smoke.json"))
    });
    let bytes = fs::read(&path).with_context(|| {
        format!(
            "UI smoke testing requires a JSON specification: {}",
            path.display()
        )
    })?;
    if bytes.len() > 32 * 1024 {
        bail!("UI smoke specification exceeds the 32 KiB transport safety limit");
    }
    let value: Value = serde_json::from_slice(&bytes)
        .with_context(|| format!("parse UI smoke specification {}", path.display()))?;
    validate_smoke_spec(&value)?;
    Ok(Some(serde_json::to_string(&value)?))
}

fn validate_smoke_spec(value: &Value) -> Result<()> {
    let object = value
        .as_object()
        .context("UI smoke specification must be a JSON object")?;
    if object.get("schemaVersion").and_then(Value::as_u64) != Some(1) {
        bail!("UI smoke specification schemaVersion must be 1");
    }
    let steps = object
        .get("steps")
        .and_then(Value::as_array)
        .context("UI smoke specification requires a steps array")?;
    if steps.is_empty() || steps.len() > 32 {
        bail!("UI smoke specification must contain between 1 and 32 steps");
    }
    for key in object.keys() {
        if !matches!(key.as_str(), "schemaVersion" | "steps") {
            bail!("UI smoke specification has unsupported root field {key}");
        }
    }
    let mut total_wait_ms = 0i64;
    for (index, step) in steps.iter().enumerate() {
        let step = step
            .as_object()
            .with_context(|| format!("smoke step {index} must be an object"))?;
        let action = step
            .get("action")
            .and_then(Value::as_str)
            .with_context(|| format!("smoke step {index} requires action"))?;
        if !matches!(
            action,
            "wait"
                | "assertDisplayed"
                | "assertText"
                | "click"
                | "focus"
                | "input"
                | "scroll"
                | "assertImeActive"
        ) {
            bail!("smoke step {index} has unsupported action {action}");
        }
        let allowed_fields: &[&str] = match action {
            "wait" => &["action", "waitMs"],
            "assertDisplayed" | "click" | "focus" | "assertImeActive" => {
                &["action", "view", "waitMs"]
            }
            "assertText" => &["action", "view", "text", "contains", "waitMs"],
            "input" => &["action", "view", "text", "waitMs"],
            "scroll" => &["action", "view", "dx", "dy", "waitMs"],
            _ => unreachable!(),
        };
        for key in step.keys() {
            if !allowed_fields.contains(&key.as_str()) {
                bail!("smoke step {index} field {key} is not valid for {action}");
            }
        }
        let view = step.get("view");
        if action != "wait" && view.is_none() {
            bail!("smoke step {index} action {action} requires view");
        }
        if let Some(view) = view {
            let view = view
                .as_str()
                .with_context(|| format!("smoke step {index} view must be a string"))?;
            let normalized = view
                .strip_prefix("@id/")
                .or_else(|| view.strip_prefix("id/"))
                .unwrap_or(view);
            if normalized.is_empty()
                || view.len() > 160
                || !normalized
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '.' | ':'))
            {
                bail!("smoke step {index} has an unsafe view name");
            }
        }
        if matches!(action, "assertText" | "input") && !step.contains_key("text") {
            bail!("smoke step {index} action {action} requires text");
        }
        if let Some(text) = step.get("text") {
            let text = text
                .as_str()
                .with_context(|| format!("smoke step {index} text must be a string"))?;
            if text.len() > 2048 {
                bail!("smoke step {index} text exceeds 2048 bytes");
            }
        }
        if let Some(contains) = step.get("contains")
            && !contains.is_boolean()
        {
            bail!("smoke step {index} contains must be a boolean");
        }
        let wait = match step.get("waitMs") {
            Some(wait) => wait
                .as_i64()
                .with_context(|| format!("smoke step {index} waitMs must be an integer"))?,
            None => 0,
        };
        if !(0..=5_000).contains(&wait) {
            bail!("smoke step {index} waitMs must be between 0 and 5000");
        }
        if action == "wait" && wait == 0 {
            bail!("smoke step {index} wait action requires a positive waitMs");
        }
        total_wait_ms += wait;
        for key in ["dx", "dy"] {
            if let Some(value) = step.get(key) {
                let value = value
                    .as_i64()
                    .with_context(|| format!("smoke step {index} {key} must be an integer"))?;
                if !(-10_000..=10_000).contains(&value) {
                    bail!("smoke step {index} {key} is out of bounds");
                }
            }
        }
        if action == "scroll"
            && step.get("dx").and_then(Value::as_i64).unwrap_or(0) == 0
            && step.get("dy").and_then(Value::as_i64).unwrap_or(0) == 0
        {
            bail!("smoke step {index} scroll requires a non-zero dx or dy");
        }
    }
    if total_wait_ms > 10_000 {
        bail!("UI smoke specification total waitMs exceeds 10000");
    }
    Ok(())
}

fn call(
    context: &AppContext,
    method: &str,
    plugin_id: Option<&str>,
    smoke_spec: Option<&str>,
) -> Result<String> {
    let am = activity_manager_binary();
    if !am.is_file() {
        bail!(
            "Android activity-manager command not found at {}; lifecycle control must run inside Termux",
            am.display()
        );
    }
    let request_id = request_id()?;
    let response_path = context
        .shadow_home
        .join("reports/control")
        .join(format!("{request_id}.json"));
    if response_path.exists() {
        fs::remove_file(&response_path)?;
    }
    let mut command = Command::new(&am);
    command
        .arg("broadcast")
        .arg("--user")
        .arg(android_user_id()?.to_string())
        .arg("-W")
        .arg("--receiver-foreground")
        .arg("-n")
        .arg(context.control_component())
        .arg("-a")
        .arg(context.control_action())
        .arg("--es")
        .arg("method")
        .arg(method)
        .arg("--es")
        .arg("requestId")
        .arg(&request_id);
    if let Some(plugin_id) = plugin_id {
        command.arg("--es").arg("pluginId").arg(plugin_id);
    }
    if let Some(smoke_spec) = smoke_spec {
        command.arg("--es").arg("smokeSpec").arg(smoke_spec);
    }
    let output = command
        .output()
        .with_context(|| format!("invoke Host control method {method}"))?;
    let stdout = String::from_utf8_lossy(&output.stdout).trim().to_owned();
    let stderr = String::from_utf8_lossy(&output.stderr).trim().to_owned();
    if !output.status.success() {
        bail!(
            "Host control {} failed with {}: {}{}",
            method,
            output.status,
            stderr,
            if stdout.is_empty() {
                String::new()
            } else {
                format!("; {stdout}")
            }
        );
    }
    if stdout.contains("SecurityException") || stderr.contains("SecurityException") {
        bail!("Host control broadcast was denied: {stdout} {stderr}");
    }

    let started = Instant::now();
    let timeout = Duration::from_secs(35);
    loop {
        if response_path.is_file() {
            let bytes = fs::read(&response_path)
                .with_context(|| format!("read {}", response_path.display()))?;
            let response: HostResponse = serde_json::from_slice(&bytes)
                .with_context(|| format!("parse {}", response_path.display()))?;
            if response.request_id != request_id {
                bail!("Host control response requestId does not match request");
            }
            if response.method.as_deref() != Some(method) {
                bail!("Host control response method does not match {method}");
            }
            if response.plugin_id.as_deref() != plugin_id {
                bail!("Host control response pluginId does not match request");
            }
            if response.status != "OK" {
                bail!(
                    "Host rejected control method {method}: {}",
                    response.message
                );
            }
            return Ok(response.message);
        }
        if started.elapsed() >= timeout {
            bail!(
                "Host control {} timed out waiting for {} (am output: {} {})",
                method,
                response_path.display(),
                stdout,
                stderr
            );
        }
        thread::sleep(Duration::from_millis(100));
    }
}

fn request_id() -> Result<String> {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .context("system clock is before Unix epoch")?
        .as_nanos();
    Ok(format!("cli-{}-{nanos}", std::process::id()))
}

fn android_user_id() -> Result<u32> {
    if let Ok(value) = std::env::var("TERMUX_SHADOW_ANDROID_USER") {
        return value
            .parse::<u32>()
            .context("TERMUX_SHADOW_ANDROID_USER must be a non-negative integer");
    }
    let status = fs::read_to_string("/proc/self/status").context("read /proc/self/status")?;
    let uid = status
        .lines()
        .find_map(|line| line.strip_prefix("Uid:"))
        .and_then(|values| values.split_whitespace().next())
        .context("/proc/self/status does not contain Uid")?
        .parse::<u32>()
        .context("parse process UID")?;
    // Android allocates 100000 Linux UIDs per Android user/profile.
    Ok(uid / 100_000)
}

fn emit_control(
    context: &AppContext,
    method: &'static str,
    plugin_id: Option<&str>,
    status: &'static str,
    message: String,
) -> Result<()> {
    if context.json {
        println!(
            "{}",
            serde_json::to_string_pretty(&ControlResult {
                ok: true,
                method,
                plugin_id,
                status,
                message,
            })?
        );
    } else if let Some(plugin_id) = plugin_id {
        println!("{method}: {status}  {plugin_id}");
        println!("  {message}");
    } else {
        println!("{method}: {status}");
        println!("  {message}");
    }
    Ok(())
}

fn activity_manager_binary() -> PathBuf {
    PathBuf::from("/system/bin/am")
}

#[cfg(test)]
mod tests {
    use super::{request_id, validate_smoke_spec};
    use serde_json::json;

    #[test]
    fn request_ids_are_path_safe() {
        let request_id = request_id().unwrap();
        assert!(request_id.starts_with("cli-"));
        assert!(
            request_id
                .chars()
                .all(|character| character.is_ascii_alphanumeric() || character == '-')
        );
    }

    #[test]
    fn smoke_spec_is_bounded_and_declarative() {
        assert!(
            validate_smoke_spec(&json!({
                "schemaVersion": 1,
                "steps": [
                    {"action": "assertDisplayed", "view": "@id/message_list"},
                    {"action": "scroll", "view": "message_list", "dy": 300}
                ]
            }))
            .is_ok()
        );
        assert!(
            validate_smoke_spec(&json!({
                "schemaVersion": 1,
                "steps": [{"action": "shell", "text": "rm"}]
            }))
            .is_err()
        );
        assert!(
            validate_smoke_spec(&json!({
                "schemaVersion": 1,
                "steps": [{"action": "wait", "waitMs": 6000}]
            }))
            .is_err()
        );
        assert!(
            validate_smoke_spec(&json!({
                "schemaVersion": 1,
                "steps": [{"action": "assertText", "view": "title"}]
            }))
            .is_err()
        );
        assert!(
            validate_smoke_spec(&json!({
                "schemaVersion": 1,
                "steps": [
                    {"action": "wait", "waitMs": 5000},
                    {"action": "wait", "waitMs": 5000},
                    {"action": "wait", "waitMs": 1}
                ]
            }))
            .is_err()
        );
    }
}
