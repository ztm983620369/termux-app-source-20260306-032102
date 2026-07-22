use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use serde_json::{Value, json};

const MAX_CRASH_REPORT_BYTES: u64 = 2 * 1024 * 1024;
const MAX_FALLBACK_REPORTS: usize = 512;

#[derive(Debug, Clone)]
pub struct RuntimeCrash {
    path: PathBuf,
    report: Value,
}

impl RuntimeCrash {
    pub fn operation_id(&self) -> Option<&str> {
        correlation(&self.report, "operationId")
    }

    pub fn plugin_id(&self) -> Option<&str> {
        correlation(&self.report, "pluginId")
    }

    pub fn generation(&self) -> Option<&str> {
        correlation(&self.report, "generation")
    }

    pub fn activity(&self) -> Option<&str> {
        correlation(&self.report, "activityClassName")
            .or_else(|| correlation(&self.report, "activity"))
    }

    pub fn error_type(&self) -> Option<&str> {
        self.report.get("errorType").and_then(Value::as_str)
    }

    pub fn message(&self) -> Option<&str> {
        self.report.get("message").and_then(Value::as_str)
    }

    pub fn stack_trace(&self) -> Option<&str> {
        self.report.get("stackTrace").and_then(Value::as_str)
    }

    pub fn report(&self) -> &Value {
        &self.report
    }

    pub fn relative_path(&self, shadow_home: &Path) -> String {
        self.path
            .strip_prefix(shadow_home)
            .unwrap_or(&self.path)
            .to_string_lossy()
            .into_owned()
    }

    pub fn diagnostic_message(&self) -> String {
        match (self.error_type(), self.message()) {
            (Some(kind), Some(message)) if !message.trim().is_empty() => {
                format!("{kind}: {message}")
            }
            (Some(kind), _) => kind.to_owned(),
            (_, Some(message)) if !message.trim().is_empty() => message.to_owned(),
            _ => "plugin process crashed before runtime health proof".to_owned(),
        }
    }

    pub fn diagnostic_json(&self) -> Value {
        json!({
            "kind": "RUNTIME_CRASH",
            "activity": self.activity(),
            "errorType": self.error_type(),
            "message": self.diagnostic_message(),
        })
    }

    pub fn render_log(&self) -> String {
        let mut output = String::new();
        for (label, value) in [
            ("operationId", self.operation_id()),
            ("pluginId", self.plugin_id()),
            ("generation", self.generation()),
            ("activity", self.activity()),
            ("errorType", self.error_type()),
            ("message", self.message()),
        ] {
            if let Some(value) = value {
                output.push_str(label);
                output.push_str(": ");
                output.push_str(value);
                output.push('\n');
            }
        }
        if let Some(stack_trace) = self.stack_trace() {
            output.push_str("\nstackTrace:\n");
            output.push_str(stack_trace);
            if !stack_trace.ends_with('\n') {
                output.push('\n');
            }
        }
        output
    }
}

pub fn find(
    shadow_home: &Path,
    operation_id: &str,
    plugin_id: Option<&str>,
    generation: Option<&str>,
) -> Result<Option<RuntimeCrash>> {
    if !safe_segment(operation_id) {
        return Ok(None);
    }
    let correlated = shadow_home
        .join("reports/runtime-crash")
        .join(format!("{operation_id}.json"));
    if let Ok(Some(report)) = read_matching(&correlated, operation_id, plugin_id, generation) {
        return Ok(Some(report));
    }

    let crash_dir = shadow_home.join("crash");
    let Ok(entries) = fs::read_dir(&crash_dir) else {
        return Ok(None);
    };
    let mut matches = Vec::new();
    for entry in entries.filter_map(Result::ok).take(MAX_FALLBACK_REPORTS) {
        if !entry.file_type().is_ok_and(|kind| kind.is_file())
            || entry.path().extension().and_then(|value| value.to_str()) != Some("json")
        {
            continue;
        }
        if let Ok(Some(report)) = read_matching(&entry.path(), operation_id, plugin_id, generation)
        {
            let epoch_ms = report
                .report
                .get("epochMs")
                .and_then(Value::as_u64)
                .unwrap_or_default();
            matches.push((epoch_ms, report));
        }
    }
    matches.sort_by_key(|(epoch_ms, _)| *epoch_ms);
    Ok(matches.pop().map(|(_, report)| report))
}

pub fn operation_from_result(value: &Value) -> Option<(&str, Option<&str>, Option<&str>)> {
    let launch = value.get("launch");
    let operation_id = launch
        .and_then(|item| {
            item.get("hostOperationId")
                .or_else(|| item.get("operationId"))
        })
        .and_then(Value::as_str)
        .or_else(|| value.get("hostOperationId").and_then(Value::as_str))
        .or_else(|| value.get("operationId").and_then(Value::as_str))
        .or_else(|| {
            value
                .get("runtimeProof")
                .and_then(|item| item.get("hostOperationId"))
                .and_then(Value::as_str)
        })?;
    let plugin_id = launch
        .and_then(|item| item.get("pluginId"))
        .and_then(Value::as_str)
        .or_else(|| value.get("pluginId").and_then(Value::as_str));
    let generation = launch
        .and_then(|item| item.get("generation"))
        .and_then(Value::as_str)
        .or_else(|| value.get("generation").and_then(Value::as_str));
    Some((operation_id, plugin_id, generation))
}

fn read_matching(
    path: &Path,
    operation_id: &str,
    plugin_id: Option<&str>,
    generation: Option<&str>,
) -> Result<Option<RuntimeCrash>> {
    if !path
        .symlink_metadata()
        .is_ok_and(|metadata| metadata.file_type().is_file())
    {
        return Ok(None);
    }
    let metadata = path
        .symlink_metadata()
        .with_context(|| format!("inspect runtime crash report {}", path.display()))?;
    if metadata.len() > MAX_CRASH_REPORT_BYTES {
        return Ok(None);
    }
    let bytes =
        fs::read(path).with_context(|| format!("read runtime crash report {}", path.display()))?;
    let report: Value = serde_json::from_slice(&bytes)
        .with_context(|| format!("parse runtime crash report {}", path.display()))?;
    if correlation(&report, "operationId") != Some(operation_id)
        || plugin_id.is_some_and(|expected| correlation(&report, "pluginId") != Some(expected))
        || generation.is_some_and(|expected| correlation(&report, "generation") != Some(expected))
    {
        return Ok(None);
    }
    Ok(Some(RuntimeCrash {
        path: path.to_path_buf(),
        report,
    }))
}

fn correlation<'a>(report: &'a Value, key: &str) -> Option<&'a str> {
    report.get(key).and_then(Value::as_str).or_else(|| {
        report
            .get("launchContext")
            .and_then(|context| context.get(key))
            .and_then(Value::as_str)
    })
}

fn safe_segment(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value.chars().all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | '-')
        })
}

#[cfg(test)]
mod tests {
    use super::{find, operation_from_result};
    use serde_json::json;
    use std::fs;

    #[test]
    fn selects_the_exact_correlated_runtime_crash() {
        let temp = tempfile::tempdir().unwrap();
        let directory = temp.path().join("reports/runtime-crash");
        fs::create_dir_all(&directory).unwrap();
        fs::write(
            directory.join("launch-123.json"),
            serde_json::to_vec(&json!({
                "schemaVersion": 2,
                "epochMs": 10,
                "operationId": "launch-123",
                "pluginId": "com.termux.shadow.notes",
                "generation": "14-deadbeef",
                "activityClassName": "com.termux.shadow.notes.NotesActivity",
                "errorType": "java.lang.IllegalStateException",
                "message": "boom",
                "stackTrace": "java.lang.IllegalStateException: boom\n  at NotesActivity.onCreate(NotesActivity.java:20)"
            }))
            .unwrap(),
        )
        .unwrap();

        let crash = find(
            temp.path(),
            "launch-123",
            Some("com.termux.shadow.notes"),
            Some("14-deadbeef"),
        )
        .unwrap()
        .unwrap();
        assert_eq!(
            crash.activity(),
            Some("com.termux.shadow.notes.NotesActivity")
        );
        assert!(crash.render_log().contains("NotesActivity.java:20"));
        assert!(
            find(
                temp.path(),
                "launch-123",
                Some("com.termux.shadow.other"),
                None,
            )
            .unwrap()
            .is_none()
        );
    }

    #[test]
    fn extracts_nested_deploy_launch_correlation() {
        let value = json!({
            "operationId": "op-deploy-history",
            "launch": {
                "operationId": "launch-runtime",
                "pluginId": "com.termux.shadow.notes",
                "generation": "14-deadbeef"
            }
        });
        let correlation = operation_from_result(&value).unwrap();
        // A deploy's top-level operation is development history, while the nested launch operation
        // is the Host runtime correlation and must win.
        assert_eq!(correlation.0, "launch-runtime");
    }

    #[test]
    fn malformed_unrelated_crash_files_do_not_hide_an_exact_legacy_match() {
        let temp = tempfile::tempdir().unwrap();
        let crash_dir = temp.path().join("crash");
        fs::create_dir_all(&crash_dir).unwrap();
        fs::write(crash_dir.join("broken.json"), b"not-json").unwrap();
        fs::write(
            crash_dir.join("legacy.json"),
            serde_json::to_vec(&json!({
                "epochMs": 20,
                "errorType": "java.lang.RuntimeException",
                "stackTrace": "boom",
                "launchContext": {
                    "operationId": "legacy-launch",
                    "pluginId": "com.termux.shadow.notes",
                    "generation": "15-legacy"
                }
            }))
            .unwrap(),
        )
        .unwrap();
        assert!(
            find(
                temp.path(),
                "legacy-launch",
                Some("com.termux.shadow.notes"),
                Some("15-legacy")
            )
            .unwrap()
            .is_some()
        );
    }
}
