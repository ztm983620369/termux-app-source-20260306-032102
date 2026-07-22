use std::fs;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};

use crate::fsutil::write_atomic;

const HISTORY_SCHEMA_VERSION: u32 = 1;
const MAX_RECORDS: usize = 256;
const MAX_EXISTING_BYTES: u64 = 2 * 1024 * 1024;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OperationRecord {
    pub schema_version: u32,
    pub operation_id: String,
    pub recorded_at: u64,
    pub action: String,
    pub status: String,
    pub plugin_id: String,
    pub project: String,
    pub source_fingerprint: String,
    pub toolchain_fingerprint: String,
    pub input_fingerprint: Option<String>,
    pub version_code: Option<u64>,
    pub version_name: Option<String>,
    pub artifact_sha256: Option<String>,
    pub duration_ms: u64,
    pub state_before: Option<String>,
    pub state_after: Option<String>,
    pub error_code: Option<String>,
}

impl OperationRecord {
    pub fn new(
        operation_id: String,
        action: &str,
        status: &str,
        plugin_id: &str,
        project: &Path,
        source_fingerprint: String,
        toolchain_fingerprint: String,
    ) -> Self {
        Self {
            schema_version: HISTORY_SCHEMA_VERSION,
            operation_id,
            recorded_at: now_millis(),
            action: action.to_owned(),
            status: status.to_owned(),
            plugin_id: plugin_id.to_owned(),
            project: project.display().to_string(),
            source_fingerprint,
            toolchain_fingerprint,
            input_fingerprint: None,
            version_code: None,
            version_name: None,
            artifact_sha256: None,
            duration_ms: 0,
            state_before: None,
            state_after: None,
            error_code: None,
        }
    }
}

pub fn append(shadow_home: &Path, record: &OperationRecord) -> Result<PathBuf> {
    let path = history_path(shadow_home, &record.plugin_id);
    let mut retained = if path
        .metadata()
        .map(|metadata| metadata.len() <= MAX_EXISTING_BYTES)
        .unwrap_or(false)
    {
        fs::read_to_string(&path)
            .unwrap_or_default()
            .lines()
            .filter(|line| !line.trim().is_empty())
            .map(str::to_owned)
            .collect::<Vec<_>>()
    } else {
        Vec::new()
    };
    if retained.len() >= MAX_RECORDS {
        retained.drain(..=retained.len() - MAX_RECORDS);
    }
    retained.push(serde_json::to_string(record)?);
    let mut contents = retained.join("\n");
    contents.push('\n');
    write_atomic(&path, contents.as_bytes())?;
    Ok(path)
}

pub fn latest_for_source(
    shadow_home: &Path,
    plugin_id: &str,
    source_fingerprint: &str,
) -> Result<Option<OperationRecord>> {
    let path = history_path(shadow_home, plugin_id);
    if !path.is_file() {
        return Ok(None);
    }
    let contents = fs::read_to_string(&path)
        .with_context(|| format!("read operation history {}", path.display()))?;
    for line in contents.lines().rev() {
        let Ok(record) = serde_json::from_str::<OperationRecord>(line) else {
            continue;
        };
        if record.schema_version == HISTORY_SCHEMA_VERSION
            && record.plugin_id == plugin_id
            && record.source_fingerprint == source_fingerprint
        {
            return Ok(Some(record));
        }
    }
    Ok(None)
}

pub fn latest(shadow_home: &Path, plugin_id: &str) -> Result<Option<OperationRecord>> {
    find_latest(shadow_home, plugin_id, |_| true)
}

pub fn latest_for_artifact(
    shadow_home: &Path,
    plugin_id: &str,
    artifact_sha256: &str,
) -> Result<Option<OperationRecord>> {
    find_latest(shadow_home, plugin_id, |record| {
        record.artifact_sha256.as_deref() == Some(artifact_sha256)
    })
}

pub fn highest_version_code(shadow_home: &Path, plugin_id: &str) -> Result<Option<u64>> {
    Ok(highest_version(shadow_home, plugin_id)?.and_then(|record| record.version_code))
}

pub fn highest_version(shadow_home: &Path, plugin_id: &str) -> Result<Option<OperationRecord>> {
    let path = history_path(shadow_home, plugin_id);
    if !path.is_file() {
        return Ok(None);
    }
    let contents = fs::read_to_string(&path)
        .with_context(|| format!("read operation history {}", path.display()))?;
    Ok(contents
        .lines()
        .filter_map(|line| serde_json::from_str::<OperationRecord>(line).ok())
        .filter(|record| {
            record.schema_version == HISTORY_SCHEMA_VERSION && record.plugin_id == plugin_id
        })
        .filter(version_is_committed)
        .max_by_key(|record| record.version_code))
}

fn version_is_committed(record: &OperationRecord) -> bool {
    // Failed attempts stay auditable, but only an artifact that crossed the publish boundary
    // reserves a release number. Standalone build and doctor caches never enter this history.
    record.version_code.is_some()
        && record
            .artifact_sha256
            .as_deref()
            .is_some_and(|sha256| !sha256.is_empty())
}

fn find_latest(
    shadow_home: &Path,
    plugin_id: &str,
    predicate: impl Fn(&OperationRecord) -> bool,
) -> Result<Option<OperationRecord>> {
    let path = history_path(shadow_home, plugin_id);
    if !path.is_file() {
        return Ok(None);
    }
    let contents = fs::read_to_string(&path)
        .with_context(|| format!("read operation history {}", path.display()))?;
    for line in contents.lines().rev() {
        let Ok(record) = serde_json::from_str::<OperationRecord>(line) else {
            continue;
        };
        if record.schema_version == HISTORY_SCHEMA_VERSION
            && record.plugin_id == plugin_id
            && predicate(&record)
        {
            return Ok(Some(record));
        }
    }
    Ok(None)
}

pub fn operation_id(prefix: &str) -> String {
    if let Ok(value) = std::env::var("TERMUX_SHADOW_OPERATION_ID")
        && is_safe_operation_id(&value)
    {
        return value;
    }
    format!("{prefix}-{}-{}", std::process::id(), now_millis())
}

fn is_safe_operation_id(value: &str) -> bool {
    (8..=96).contains(&value.len())
        && value.chars().all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | '-')
        })
}

fn history_path(shadow_home: &Path, plugin_id: &str) -> PathBuf {
    let safe_plugin_id = plugin_id
        .chars()
        .map(|character| {
            if character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | '-') {
                character
            } else {
                '_'
            }
        })
        .collect::<String>();
    shadow_home
        .join("history")
        .join(format!("{safe_plugin_id}.jsonl"))
}

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .try_into()
        .unwrap_or(u64::MAX)
}

#[cfg(test)]
mod tests {
    use super::{OperationRecord, append, highest_version, latest_for_source};

    #[test]
    fn history_round_trips_latest_source_record() {
        let temp = tempfile::tempdir().unwrap();
        let record = OperationRecord::new(
            "dev-1".into(),
            "dev",
            "ACTIVE",
            "com.termux.shadow.notes",
            temp.path(),
            "source-a".into(),
            "tools-a".into(),
        );
        let path = append(temp.path(), &record).unwrap();
        assert!(path.is_file());
        let loaded = latest_for_source(temp.path(), "com.termux.shadow.notes", "source-a")
            .unwrap()
            .unwrap();
        assert_eq!(loaded.operation_id, "dev-1");
    }

    #[test]
    fn compile_failure_does_not_commit_the_allocated_version() {
        let temp = tempfile::tempdir().unwrap();
        let mut committed = OperationRecord::new(
            "deploy-committed".into(),
            "deploy",
            "ACTIVE",
            "com.termux.shadow.notes",
            temp.path(),
            "source-a".into(),
            "tools-a".into(),
        );
        committed.version_code = Some(16);
        committed.version_name = Some("2.1.5".into());
        committed.artifact_sha256 = Some("a".repeat(64));
        append(temp.path(), &committed).unwrap();

        let mut failed = OperationRecord::new(
            "deploy-compile-failed".into(),
            "deploy",
            "FAILED",
            "com.termux.shadow.notes",
            temp.path(),
            "source-b".into(),
            "tools-a".into(),
        );
        failed.version_code = Some(17);
        failed.version_name = Some("2.1.6".into());
        failed.error_code = Some("JAVA_COMPILE_ERROR".into());
        append(temp.path(), &failed).unwrap();

        let highest = highest_version(temp.path(), "com.termux.shadow.notes")
            .unwrap()
            .unwrap();
        assert_eq!(highest.version_code, Some(16));
        assert_eq!(highest.operation_id, "deploy-committed");
    }

    #[test]
    fn validated_artifact_commits_version_even_if_activation_fails() {
        let temp = tempfile::tempdir().unwrap();
        let mut failed = OperationRecord::new(
            "deploy-activation-failed".into(),
            "deploy",
            "ACTIVATION_FAILED",
            "com.termux.shadow.notes",
            temp.path(),
            "source-c".into(),
            "tools-a".into(),
        );
        failed.version_code = Some(18);
        failed.version_name = Some("2.1.7".into());
        failed.artifact_sha256 = Some("b".repeat(64));
        failed.error_code = Some("ACTIVATION_CRASHED".into());
        append(temp.path(), &failed).unwrap();

        let highest = highest_version(temp.path(), "com.termux.shadow.notes")
            .unwrap()
            .unwrap();
        assert_eq!(highest.version_code, Some(18));
    }
}
