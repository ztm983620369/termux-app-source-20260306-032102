use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::SystemTime;

use anyhow::{Context, Result, bail};
use regex::Regex;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use crate::cli::EvidenceArgs;
use crate::context::AppContext;
use crate::fsutil::{sha256_file, write_atomic};

const EVIDENCE_SCHEMA_VERSION: u32 = 1;
const MAX_RETAINED_SUCCESSES: usize = 100;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EvidenceRef {
    #[serde(rename = "evidenceId", alias = "id")]
    pub id: String,
    pub sha256: String,
    pub bytes: u64,
    pub complete: bool,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct EvidenceFile {
    path: String,
    sha256: String,
    bytes: u64,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct EvidenceManifest {
    schema_version: u32,
    operation_id: String,
    complete: bool,
    ok: bool,
    exit_code: i32,
    redacted_values: usize,
    files: Vec<EvidenceFile>,
}

pub struct EvidenceCapture {
    shadow_home: PathBuf,
    root: PathBuf,
    operation_id: String,
    redacted_values: usize,
    project: Option<PathBuf>,
    build_logs_before: BTreeMap<String, (u64, Option<SystemTime>)>,
}

impl EvidenceCapture {
    pub fn begin(
        shadow_home: &Path,
        operation_id: &str,
        request: &Value,
        registry_before: Option<&[u8]>,
        project: Option<&Path>,
    ) -> Result<Self> {
        require_safe_id(operation_id)?;
        let evidence_root = shadow_home.join("evidence");
        create_private_dir(&evidence_root)?;
        let root = evidence_root.join(operation_id);
        if root.exists() {
            bail!("evidence operation already exists: {operation_id}");
        }
        create_private_dir(&root)?;
        write_json(&root.join("request.json"), request)?;
        write_atomic(
            &root.join("state-before.json"),
            registry_before.unwrap_or(b"{}\n"),
        )?;
        Ok(Self {
            shadow_home: shadow_home.to_path_buf(),
            root,
            operation_id: operation_id.to_owned(),
            redacted_values: 0,
            project: project.map(Path::to_path_buf),
            build_logs_before: project.map(snapshot_build_logs).unwrap_or_default(),
        })
    }

    pub fn finish(
        mut self,
        action: &str,
        exit_code: i32,
        stdout: &[u8],
        stderr: &[u8],
        duration_ms: u64,
        registry_after: Option<&[u8]>,
    ) -> Result<EvidenceRef> {
        let (stdout, stdout_redactions) = redact_output(stdout);
        let (stderr, stderr_redactions) = redact_output(stderr);
        self.redacted_values = stdout_redactions.saturating_add(stderr_redactions);
        write_atomic(&self.root.join("stdout.log"), &stdout)?;
        write_atomic(&self.root.join("stderr.log"), &stderr)?;
        write_atomic(
            &self.root.join("state-after.json"),
            registry_after.unwrap_or(b"{}\n"),
        )?;

        let parsed = serde_json::from_slice::<Value>(&stdout).ok();
        let result = parsed.clone().unwrap_or_else(|| {
            json!({
                "ok": exit_code == 0,
                "action": action,
                "exitCode": exit_code,
                "outputFormat": "human"
            })
        });
        write_json(&self.root.join("result.json"), &result)?;
        let runtime_crash = if let Some((operation_id, plugin_id, generation)) = parsed
            .as_ref()
            .and_then(crate::runtime_crash::operation_from_result)
        {
            crate::runtime_crash::find(&self.shadow_home, operation_id, plugin_id, generation)?
        } else {
            None
        };
        let mut diagnostics = parsed
            .as_ref()
            .and_then(|value| value.get("diagnostics"))
            .cloned()
            .unwrap_or_else(|| Value::Array(Vec::new()));
        if let Some(crash) = &runtime_crash
            && diagnostics.as_array().is_some_and(Vec::is_empty)
        {
            diagnostics = Value::Array(vec![crash.diagnostic_json()]);
        }
        write_json(&self.root.join("diagnostics.json"), &diagnostics)?;

        if let Some(crash) = runtime_crash {
            let raw = serde_json::to_vec_pretty(crash.report())?;
            let (raw, raw_redactions) = redact_output(&raw);
            let (log, log_redactions) = redact_output(crash.render_log().as_bytes());
            self.redacted_values = self
                .redacted_values
                .saturating_add(raw_redactions)
                .saturating_add(log_redactions);
            write_atomic(&self.root.join("runtime-crash.json"), &raw)?;
            write_atomic(&self.root.join("runtime-crash.log"), &log)?;
        }

        if let Some(project) = &self.project {
            let log_directory = project.join("build/logs");
            if log_directory.is_dir() {
                for entry in fs::read_dir(&log_directory)? {
                    let entry = entry?;
                    if !entry.file_type()?.is_file() {
                        continue;
                    }
                    let name = entry.file_name().to_string_lossy().into_owned();
                    let metadata = entry.metadata()?;
                    let signature = (metadata.len(), metadata.modified().ok());
                    if self.build_logs_before.get(&name) == Some(&signature) {
                        continue;
                    }
                    let (contents, redactions) = redact_output(&fs::read(entry.path())?);
                    self.redacted_values = self.redacted_values.saturating_add(redactions);
                    write_atomic(&self.root.join(format!("gradle-{name}")), &contents)?;
                }
            }
        }
        write_json(
            &self.root.join("timing.json"),
            &json!({"durationMs": duration_ms}),
        )?;
        write_json(
            &self.root.join("artifact-manifest.json"),
            &artifact_summary(parsed.as_ref()),
        )?;
        write_json(
            &self.root.join("redaction.json"),
            &json!({
                "applied": self.redacted_values > 0,
                "valuesRedacted": self.redacted_values,
                "policy": "secret environment values and credential-shaped text"
            }),
        )?;

        let mut paths = fs::read_dir(&self.root)?
            .filter_map(Result::ok)
            .map(|entry| entry.path())
            .filter(|path| path.is_file())
            .collect::<Vec<_>>();
        paths.sort();
        let mut files = Vec::new();
        for path in paths {
            let name = path
                .file_name()
                .unwrap_or_default()
                .to_string_lossy()
                .into_owned();
            files.push(EvidenceFile {
                path: name,
                sha256: sha256_file(&path)?,
                bytes: path.metadata()?.len(),
            });
        }
        let captured_bytes = files.iter().map(|file| file.bytes).sum::<u64>();
        let manifest = EvidenceManifest {
            schema_version: EVIDENCE_SCHEMA_VERSION,
            operation_id: self.operation_id.clone(),
            complete: true,
            ok: exit_code == 0,
            exit_code,
            redacted_values: self.redacted_values,
            files,
        };
        let manifest_path = self.root.join("evidence-manifest.json");
        write_json(&manifest_path, &manifest)?;
        let reference = EvidenceRef {
            id: self.operation_id,
            sha256: sha256_file(&manifest_path)?,
            bytes: captured_bytes.saturating_add(manifest_path.metadata()?.len()),
            complete: true,
        };
        if exit_code == 0 {
            let _ = prune_successes(self.root.parent().unwrap_or(&self.root));
        }
        Ok(reference)
    }
}

pub fn run(context: &AppContext, args: EvidenceArgs) -> Result<()> {
    require_safe_id(&args.operation_id)?;
    let root = context
        .shadow_home
        .join("evidence")
        .join(&args.operation_id);
    if !root.is_dir() {
        bail!("EVIDENCE_NOT_FOUND: {}", args.operation_id);
    }
    let value = if args.diagnostics {
        read_json_value(&root.join("diagnostics.json"))?
    } else if let Some(lines) = args.tail {
        json!({
            "evidenceId": args.operation_id,
            "stdout": tail(&fs::read_to_string(root.join("stdout.log"))?, lines),
            "stderr": tail(&fs::read_to_string(root.join("stderr.log"))?, lines),
        })
    } else if args.full {
        let mut files = BTreeMap::new();
        for entry in fs::read_dir(&root)? {
            let entry = entry?;
            if entry.file_type()?.is_file() {
                let name = entry.file_name().to_string_lossy().into_owned();
                files.insert(name, fs::read_to_string(entry.path())?);
            }
        }
        json!({"evidenceId": args.operation_id, "files": files})
    } else {
        json!({
            "evidenceId": args.operation_id,
            "result": read_json_value(&root.join("result.json"))?,
            "evidence": read_json_value(&root.join("evidence-manifest.json"))?,
        })
    };
    if context.json || args.full || args.tail.is_some() {
        if context.verbose || args.full || args.tail.is_some() {
            println!("{}", serde_json::to_string_pretty(&value)?);
        } else {
            println!("{}", serde_json::to_string(&value)?);
        }
    } else {
        println!("evidence: {}", args.operation_id);
        println!("{}", serde_json::to_string_pretty(&value)?);
    }
    Ok(())
}

fn artifact_summary(value: Option<&Value>) -> Value {
    let Some(value) = value else {
        return json!({});
    };
    let mut summary = serde_json::Map::new();
    for key in [
        "sourceFingerprint",
        "toolchainFingerprint",
        "inputFingerprint",
        "version",
        "versionCode",
        "versionName",
        "artifact",
        "artifacts",
        "receipt",
        "activation",
    ] {
        if let Some(field) = value.get(key) {
            summary.insert(key.to_owned(), field.clone());
        }
    }
    Value::Object(summary)
}

fn redact_output(bytes: &[u8]) -> (Vec<u8>, usize) {
    let mut value = String::from_utf8_lossy(bytes).into_owned();
    let mut count = 0usize;
    for (name, secret) in std::env::vars() {
        if secret.len() >= 6 && is_secret_environment_name(&name) {
            let occurrences = value.matches(&secret).count();
            if occurrences > 0 {
                value = value.replace(&secret, "[REDACTED]");
                count = count.saturating_add(occurrences);
            }
        }
    }
    let patterns = [
        r"(?i)(authorization\s*[:=]\s*bearer\s+)[A-Za-z0-9._~+/=-]+",
        r#"(?i)([\"']?(?:token|password|secret)[\"']?\s*[:=]\s*[\"']?)[^\s\"',}]+"#,
    ];
    for pattern in patterns {
        let regex = Regex::new(pattern).expect("valid evidence redaction pattern");
        let matches = regex.find_iter(&value).count();
        if matches > 0 {
            value = regex.replace_all(&value, "${1}[REDACTED]").into_owned();
            count = count.saturating_add(matches);
        }
    }
    (value.into_bytes(), count)
}

fn is_secret_environment_name(name: &str) -> bool {
    let upper = name.to_ascii_uppercase();
    upper == "TERMUX_SHADOW_SIGNING_KEY_PKCS8"
        || ["TOKEN", "PASSWORD", "SECRET", "AUTHORIZATION"]
            .iter()
            .any(|needle| upper.contains(needle))
}

fn prune_successes(evidence_root: &Path) -> Result<()> {
    let mut successes = Vec::new();
    for entry in fs::read_dir(evidence_root)? {
        let entry = entry?;
        if !entry.file_type()?.is_dir() {
            continue;
        }
        let manifest_path = entry.path().join("evidence-manifest.json");
        let Some(manifest) = fs::read(&manifest_path)
            .ok()
            .and_then(|bytes| serde_json::from_slice::<EvidenceManifest>(&bytes).ok())
        else {
            continue;
        };
        if manifest.ok {
            successes.push((entry.metadata()?.modified().ok(), entry.path()));
        }
    }
    successes.sort_by_key(|(modified, _)| *modified);
    let remove = successes.len().saturating_sub(MAX_RETAINED_SUCCESSES);
    for (_, path) in successes.into_iter().take(remove) {
        fs::remove_dir_all(path)?;
    }
    Ok(())
}

fn read_json_value(path: &Path) -> Result<Value> {
    let bytes = fs::read(path).with_context(|| format!("read {}", path.display()))?;
    serde_json::from_slice(&bytes).with_context(|| format!("parse {}", path.display()))
}

fn snapshot_build_logs(project: &Path) -> BTreeMap<String, (u64, Option<SystemTime>)> {
    let directory = project.join("build/logs");
    let Ok(entries) = fs::read_dir(directory) else {
        return BTreeMap::new();
    };
    entries
        .filter_map(Result::ok)
        .filter_map(|entry| {
            let metadata = entry.metadata().ok()?;
            metadata.is_file().then(|| {
                (
                    entry.file_name().to_string_lossy().into_owned(),
                    (metadata.len(), metadata.modified().ok()),
                )
            })
        })
        .collect()
}

fn write_json<T: Serialize + ?Sized>(path: &Path, value: &T) -> Result<()> {
    let mut bytes = serde_json::to_vec_pretty(value)?;
    bytes.push(b'\n');
    write_atomic(path, &bytes)
}

fn tail(value: &str, lines: usize) -> String {
    let lines = lines.min(10_000);
    let all = value.lines().collect::<Vec<_>>();
    all[all.len().saturating_sub(lines)..].join("\n")
}

fn require_safe_id(value: &str) -> Result<()> {
    if (8..=96).contains(&value.len())
        && value.chars().all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | '-')
        })
    {
        Ok(())
    } else {
        bail!("invalid evidence operation id")
    }
}

fn create_private_dir(path: &Path) -> Result<()> {
    fs::create_dir_all(path).with_context(|| format!("create {}", path.display()))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{EvidenceCapture, EvidenceRef, is_secret_environment_name, redact_output};
    use serde_json::json;

    #[test]
    fn evidence_manifest_verifies_every_captured_file() {
        let temp = tempfile::tempdir().unwrap();
        let capture = EvidenceCapture::begin(
            temp.path(),
            "op-test-1234",
            &json!({"action": "build"}),
            Some(b"{}\n"),
            Some(temp.path()),
        )
        .unwrap();
        let reference = capture
            .finish("build", 0, br#"{"ok":true}"#, b"", 12, Some(b"{}\n"))
            .unwrap();
        assert!(reference.complete);
        assert_eq!(reference.id, "op-test-1234");
        assert!(reference.bytes > 0);
        assert!(
            temp.path()
                .join("evidence/op-test-1234/evidence-manifest.json")
                .is_file()
        );
    }

    #[test]
    fn evidence_reference_emits_the_public_name_and_reads_legacy_cache_entries() {
        let reference: EvidenceRef = serde_json::from_value(json!({
            "id": "op-legacy-1234",
            "sha256": "abc",
            "bytes": 12,
            "complete": true
        }))
        .unwrap();
        let serialized = serde_json::to_value(reference).unwrap();
        assert_eq!(serialized["evidenceId"], "op-legacy-1234");
        assert!(serialized.get("id").is_none());
    }

    #[test]
    fn credential_shaped_text_is_redacted() {
        let (redacted, count) = redact_output(b"Authorization: Bearer abcdef123456");
        assert!(count > 0);
        assert!(
            !String::from_utf8(redacted)
                .unwrap()
                .contains("abcdef123456")
        );
    }

    #[test]
    fn signing_private_key_environment_is_always_secret() {
        assert!(is_secret_environment_name(
            "TERMUX_SHADOW_SIGNING_KEY_PKCS8"
        ));
        assert!(!is_secret_environment_name("TERMUX_SHADOW_SIGNING_KEY_ID"));
    }

    #[test]
    fn runtime_crash_is_correlated_and_copied_into_operation_evidence() {
        let temp = tempfile::tempdir().unwrap();
        let crash_dir = temp.path().join("reports/runtime-crash");
        std::fs::create_dir_all(&crash_dir).unwrap();
        std::fs::write(
            crash_dir.join("launch-runtime-1.json"),
            serde_json::to_vec_pretty(&json!({
                "schemaVersion": 2,
                "operationId": "launch-runtime-1",
                "pluginId": "com.termux.shadow.notes",
                "generation": "16-deadbeef",
                "activityClassName": "com.termux.shadow.notes.NotesActivity",
                "errorType": "java.lang.IllegalStateException",
                "message": "boom",
                "stackTrace": "java.lang.IllegalStateException: boom\n at NotesActivity.onCreate(NotesActivity.java:20)"
            }))
            .unwrap(),
        )
        .unwrap();
        let capture = EvidenceCapture::begin(
            temp.path(),
            "op-deploy-1234",
            &json!({"action": "deploy"}),
            Some(b"{}\n"),
            None,
        )
        .unwrap();
        let stdout = serde_json::to_vec(&json!({
            "ok": false,
            "action": "deploy",
            "code": "ACTIVATION_FAILED",
            "operationId": "launch-runtime-1",
            "pluginId": "com.termux.shadow.notes",
            "generation": "16-deadbeef",
            "diagnostics": []
        }))
        .unwrap();
        capture
            .finish("deploy", 1, &stdout, b"", 25, Some(b"{}\n"))
            .unwrap();
        let evidence = temp.path().join("evidence/op-deploy-1234");
        assert!(evidence.join("runtime-crash.json").is_file());
        assert!(
            std::fs::read_to_string(evidence.join("runtime-crash.log"))
                .unwrap()
                .contains("NotesActivity.java:20")
        );
        let diagnostics: serde_json::Value =
            serde_json::from_slice(&std::fs::read(evidence.join("diagnostics.json")).unwrap())
                .unwrap();
        assert_eq!(diagnostics[0]["kind"], "RUNTIME_CRASH");
    }
}
