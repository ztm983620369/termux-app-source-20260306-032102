use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};

use crate::config::PluginConfig;
use crate::context::AppContext;
use crate::fsutil::{set_private_permissions, sha256_file, write_atomic};
use crate::status::{PluginRecord, VersionRecord, read_registry};

pub const ACTIVE_ARTIFACT_NAME: &str = "active.shadowpkg";
const LAST_HEALTHY_NAME: &str = "last-healthy.json";
const LAST_RUNTIME_NAME: &str = "last-runtime.json";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeArtifactReceipt {
    pub schema_version: u32,
    pub plugin_id: String,
    pub generation: Option<String>,
    pub version_code: Option<u64>,
    pub version_name: Option<String>,
    pub sha256: String,
    pub artifact_path: Option<String>,
    pub active_artifact_path: Option<String>,
    pub status: String,
    pub runtime_proven: bool,
    pub health_semantics: Option<String>,
    pub runtime_health_protocol_version: Option<u32>,
    pub runtime_stable_at: Option<u64>,
    pub last_healthy_process_pid: Option<u32>,
    pub operation_id: Option<String>,
    pub error: Option<String>,
    pub checked_at_epoch_ms: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeArtifactView {
    pub active_artifact: Option<ArtifactView>,
    pub last_healthy: Option<RuntimeArtifactReceipt>,
    pub last_runtime: Option<RuntimeArtifactReceipt>,
}

impl RuntimeArtifactView {
    pub fn compact(&self) -> RuntimeArtifactCompactView {
        RuntimeArtifactCompactView {
            active_artifact: self.active_artifact.clone(),
            last_healthy_generation: self
                .last_healthy
                .as_ref()
                .and_then(|receipt| receipt.generation.clone()),
            last_published_status: self
                .last_runtime
                .as_ref()
                .map(|receipt| receipt.status.clone()),
            last_published_proven: self
                .last_runtime
                .as_ref()
                .map(|receipt| receipt.runtime_proven),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeArtifactCompactView {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub active_artifact: Option<ArtifactView>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_healthy_generation: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_published_status: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_published_proven: Option<bool>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ArtifactView {
    pub path: String,
    pub sha256: String,
}

pub fn record_published(
    project: &Path,
    shadow_home: &Path,
    plugin_id: &str,
    version_code: u64,
    version_name: &str,
    artifact: &Path,
    sha256: &str,
) -> Result<()> {
    let receipt = RuntimeArtifactReceipt {
        schema_version: 1,
        plugin_id: plugin_id.to_owned(),
        generation: None,
        version_code: Some(version_code),
        version_name: Some(version_name.to_owned()),
        sha256: sha256.to_owned(),
        artifact_path: Some(display_project_path(project, artifact)),
        active_artifact_path: current_active_view(project, plugin_id)?.map(|view| view.path),
        status: "UNPROVEN".to_owned(),
        runtime_proven: false,
        health_semantics: None,
        runtime_health_protocol_version: None,
        runtime_stable_at: None,
        last_healthy_process_pid: None,
        operation_id: None,
        error: None,
        checked_at_epoch_ms: now_millis(),
    };
    write_runtime_receipt(project, artifact, &receipt)?;
    update_publish_receipts(project, shadow_home, plugin_id, sha256, &receipt)
}

pub fn record_healthy(
    context: &AppContext,
    plugin_id: &str,
    generation: &str,
    operation_id: Option<&str>,
) -> Result<()> {
    let Some(project) = matching_project(context, plugin_id)? else {
        return Ok(());
    };
    reconcile_at(
        &project,
        &context.shadow_home,
        plugin_id,
        Some(RuntimeAttempt {
            generation,
            operation_id,
            status: "HEALTHY",
            error: None,
        }),
    )
}

pub fn record_failure(
    context: &AppContext,
    plugin_id: &str,
    generation: Option<&str>,
    operation_id: Option<&str>,
    status: &str,
    error: &str,
) -> Result<()> {
    let Some(project) = matching_project(context, plugin_id)? else {
        return Ok(());
    };
    reconcile_at(
        &project,
        &context.shadow_home,
        plugin_id,
        generation.map(|generation| RuntimeAttempt {
            generation,
            operation_id,
            status,
            error: Some(error),
        }),
    )
}

pub fn reconcile_project(context: &AppContext, plugin_id: &str) -> Result<()> {
    if !context.shadow_home.join("reports/registry.json").is_file() {
        return Ok(());
    }
    let Some(project) = matching_project(context, plugin_id)? else {
        return Ok(());
    };
    reconcile_at(&project, &context.shadow_home, plugin_id, None)
}

pub fn view(context: &AppContext, plugin_id: &str) -> Result<Option<RuntimeArtifactView>> {
    let Some(project) = matching_project(context, plugin_id)? else {
        return Ok(None);
    };
    Ok(Some(RuntimeArtifactView {
        active_artifact: current_active_view(&project, plugin_id)?,
        last_healthy: read_runtime_receipt(&project.join("dist").join(LAST_HEALTHY_NAME)),
        last_runtime: read_runtime_receipt(&project.join("dist").join(LAST_RUNTIME_NAME)),
    }))
}

struct RuntimeAttempt<'a> {
    generation: &'a str,
    operation_id: Option<&'a str>,
    status: &'a str,
    error: Option<&'a str>,
}

fn reconcile_at(
    project: &Path,
    shadow_home: &Path,
    plugin_id: &str,
    attempt: Option<RuntimeAttempt<'_>>,
) -> Result<()> {
    let registry = read_registry(shadow_home)?;
    let plugin = registry
        .plugins
        .iter()
        .find(|plugin| plugin.plugin_id == plugin_id);
    let Some(plugin) = plugin else {
        if attempt.is_none() {
            // A newly scaffolded project has no runtime artifact state until its first publish.
            // Reconciliation is intentionally idempotent across that unregistered phase.
            return Ok(());
        }
        bail!("runtime artifact reconciliation cannot find {plugin_id}");
    };

    if let Some(active) = active_proven_version(plugin) {
        let operation_id = attempt
            .as_ref()
            .filter(|attempt| attempt.generation == active.generation)
            .and_then(|attempt| attempt.operation_id);
        materialize_healthy(project, shadow_home, plugin, active, operation_id)?;
    }

    let Some((receipt_sha, receipt)) = read_publish_receipt(project)? else {
        return Ok(());
    };
    if receipt.get("pluginId").and_then(Value::as_str) != Some(plugin_id) {
        bail!("project publish receipt does not belong to {plugin_id}");
    }
    let version = plugin
        .versions
        .iter()
        .find(|version| version.bundle_sha256 == receipt_sha);
    let (status, runtime_proven) = version
        .map(|version| runtime_status(plugin, version))
        .unwrap_or(("PUBLISHED_UNREGISTERED", false));
    let attempt_for_version = attempt
        .as_ref()
        .filter(|attempt| version.is_some_and(|version| version.generation == attempt.generation));
    let status = attempt_for_version
        .map(|attempt| normalize_attempt_status(attempt.status, runtime_proven))
        .unwrap_or(status);
    let error = attempt_for_version
        .and_then(|attempt| attempt.error)
        .map(str::to_owned)
        .or_else(|| version.and_then(|version| version.last_error.clone()));
    let artifact = find_dist_artifact(project, &receipt_sha)?;
    let runtime = runtime_receipt(
        project,
        RuntimeReceiptInput {
            plugin_id,
            version,
            sha256: &receipt_sha,
            artifact: artifact.as_deref(),
            status,
            runtime_proven,
            operation_id: attempt_for_version.and_then(|attempt| attempt.operation_id),
            error,
        },
    )?;
    if let Some(artifact) = artifact.as_deref() {
        write_runtime_receipt(project, artifact, &runtime)?;
    } else {
        write_json(&project.join("dist").join(LAST_RUNTIME_NAME), &runtime)?;
    }
    update_receipt_object(
        &project.join("dist/last-published.json"),
        plugin_id,
        &receipt_sha,
        &runtime,
    )?;
    update_receipt_object(
        &shadow_home.join("last-published.json"),
        plugin_id,
        &receipt_sha,
        &runtime,
    )?;
    Ok(())
}

fn matching_project(context: &AppContext, plugin_id: &str) -> Result<Option<PathBuf>> {
    let Some(project) = context.project_if_present() else {
        return Ok(None);
    };
    let config = PluginConfig::load(&project.join("shadow-plugin.properties"))?;
    Ok((config.plugin_id == plugin_id).then_some(project))
}

fn active_proven_version(plugin: &PluginRecord) -> Option<&VersionRecord> {
    let generation = plugin.active_generation.as_deref()?;
    plugin
        .versions
        .iter()
        .find(|version| version.generation == generation && has_runtime_proof(version))
}

fn materialize_healthy(
    project: &Path,
    shadow_home: &Path,
    plugin: &PluginRecord,
    version: &VersionRecord,
    operation_id: Option<&str>,
) -> Result<()> {
    let dist = project.join("dist");
    fs::create_dir_all(&dist)?;
    let target = dist.join(ACTIVE_ARTIFACT_NAME);
    if !target.is_file() || sha256_file(&target)? != version.bundle_sha256 {
        let source = find_artifact_source(
            project,
            shadow_home,
            &plugin.plugin_id,
            &version.generation,
            &version.bundle_sha256,
        )?
        .with_context(|| {
            format!(
                "healthy artifact {} is absent from dist and managed repository",
                version.bundle_sha256
            )
        })?;
        copy_verified_atomic(&source, &target, &version.bundle_sha256)?;
    }

    let manifest = version.manifest.as_ref();
    let receipt = RuntimeArtifactReceipt {
        schema_version: 1,
        plugin_id: plugin.plugin_id.clone(),
        generation: Some(version.generation.clone()),
        version_code: manifest.and_then(|manifest| manifest.version_code),
        version_name: manifest.and_then(|manifest| manifest.version_name.clone()),
        sha256: version.bundle_sha256.clone(),
        artifact_path: Some(format!("dist/{ACTIVE_ARTIFACT_NAME}")),
        active_artifact_path: Some(format!("dist/{ACTIVE_ARTIFACT_NAME}")),
        status: "HEALTHY".to_owned(),
        runtime_proven: true,
        health_semantics: Some("FIRST_FRAME_AND_PROCESS_STABILITY".to_owned()),
        runtime_health_protocol_version: Some(version.runtime_health_protocol_version),
        runtime_stable_at: Some(version.runtime_stable_at),
        last_healthy_process_pid: Some(version.last_healthy_process_pid),
        operation_id: operation_id.map(str::to_owned),
        error: None,
        checked_at_epoch_ms: now_millis(),
    };
    write_json(&dist.join(LAST_HEALTHY_NAME), &receipt)?;

    if let Some(source) = find_dist_artifact(project, &version.bundle_sha256)? {
        write_json(&runtime_sidecar(&source), &receipt)?;
    }
    Ok(())
}

struct RuntimeReceiptInput<'a> {
    plugin_id: &'a str,
    version: Option<&'a VersionRecord>,
    sha256: &'a str,
    artifact: Option<&'a Path>,
    status: &'a str,
    runtime_proven: bool,
    operation_id: Option<&'a str>,
    error: Option<String>,
}

fn runtime_receipt(
    project: &Path,
    input: RuntimeReceiptInput<'_>,
) -> Result<RuntimeArtifactReceipt> {
    let manifest = input.version.and_then(|version| version.manifest.as_ref());
    Ok(RuntimeArtifactReceipt {
        schema_version: 1,
        plugin_id: input.plugin_id.to_owned(),
        generation: input.version.map(|version| version.generation.clone()),
        version_code: manifest.and_then(|manifest| manifest.version_code),
        version_name: manifest.and_then(|manifest| manifest.version_name.clone()),
        sha256: input.sha256.to_owned(),
        artifact_path: input
            .artifact
            .map(|artifact| display_project_path(project, artifact)),
        active_artifact_path: current_active_view(project, input.plugin_id)?.map(|view| view.path),
        status: input.status.to_owned(),
        runtime_proven: input.runtime_proven,
        health_semantics: input
            .runtime_proven
            .then(|| "FIRST_FRAME_AND_PROCESS_STABILITY".to_owned()),
        runtime_health_protocol_version: input
            .version
            .map(|version| version.runtime_health_protocol_version),
        runtime_stable_at: input.version.map(|version| version.runtime_stable_at),
        last_healthy_process_pid: input
            .version
            .map(|version| version.last_healthy_process_pid),
        operation_id: input.operation_id.map(str::to_owned),
        error: input.error,
        checked_at_epoch_ms: now_millis(),
    })
}

fn runtime_status(plugin: &PluginRecord, version: &VersionRecord) -> (&'static str, bool) {
    if matches!(
        version.state.as_str(),
        "FAILED" | "ROLLED_BACK" | "QUARANTINED"
    ) {
        ("ACTIVATION_FAILED", false)
    } else if has_runtime_proof(version) {
        if plugin.active_generation.as_deref() == Some(version.generation.as_str()) {
            ("HEALTHY", true)
        } else {
            ("HEALTHY_SUPERSEDED", true)
        }
    } else if version.state == "ACTIVATING" {
        ("ACTIVATING", false)
    } else {
        ("UNPROVEN", false)
    }
}

fn normalize_attempt_status(status: &str, runtime_proven: bool) -> &str {
    if matches!(status, "FAILED" | "ROLLED_BACK" | "ACTIVATION_FAILED") {
        if runtime_proven {
            "LAUNCH_FAILED_ACTIVE_RETAINED"
        } else {
            "ACTIVATION_FAILED"
        }
    } else {
        status
    }
}

fn has_runtime_proof(version: &VersionRecord) -> bool {
    version.runtime_health_protocol_version >= 1
        && version.runtime_stable_at > 0
        && version.last_healthy_process_pid > 0
}

fn find_artifact_source(
    project: &Path,
    shadow_home: &Path,
    plugin_id: &str,
    generation: &str,
    sha256: &str,
) -> Result<Option<PathBuf>> {
    let active = project.join("dist").join(ACTIVE_ARTIFACT_NAME);
    if active.is_file() && sha256_file(&active)? == sha256 {
        return Ok(Some(active));
    }
    if let Some(artifact) = find_dist_artifact(project, sha256)? {
        return Ok(Some(artifact));
    }
    if !safe_segment(plugin_id) || !safe_segment(generation) {
        bail!("unsafe managed artifact identity");
    }
    for candidate in [
        shadow_home
            .join("repository/plugins")
            .join(plugin_id)
            .join(generation)
            .join("bundle.shadowpkg"),
        shadow_home
            .join("runtime/packages")
            .join(plugin_id)
            .join(generation)
            .join("bundle.shadowpkg"),
    ] {
        if candidate
            .symlink_metadata()
            .is_ok_and(|metadata| metadata.file_type().is_file())
            && sha256_file(&candidate)? == sha256
        {
            return Ok(Some(candidate));
        }
    }
    Ok(None)
}

fn find_dist_artifact(project: &Path, sha256: &str) -> Result<Option<PathBuf>> {
    let dist = project.join("dist");
    let Ok(entries) = fs::read_dir(&dist) else {
        return Ok(None);
    };
    for entry in entries {
        let entry = entry?;
        if !entry.file_type()?.is_file()
            || entry.file_name().to_string_lossy() == ACTIVE_ARTIFACT_NAME
            || entry.path().extension().and_then(|value| value.to_str()) != Some("shadowpkg")
        {
            continue;
        }
        if sha256_file(&entry.path())? == sha256 {
            return Ok(Some(entry.path()));
        }
    }
    Ok(None)
}

fn copy_verified_atomic(source: &Path, target: &Path, expected_sha256: &str) -> Result<()> {
    let parent = target.parent().context("active artifact has no parent")?;
    fs::create_dir_all(parent)?;
    let temporary = parent.join(format!(
        ".{ACTIVE_ARTIFACT_NAME}.{}.{}.tmp",
        std::process::id(),
        now_millis()
    ));
    let result = (|| -> Result<()> {
        let mut input = File::open(source)
            .with_context(|| format!("open healthy artifact {}", source.display()))?;
        let mut output = OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&temporary)
            .with_context(|| format!("stage healthy artifact {}", temporary.display()))?;
        set_private_permissions(&temporary)?;
        io::copy(&mut input, &mut output)?;
        output.flush()?;
        output.sync_all()?;
        drop(output);
        let actual_sha256 = sha256_file(&temporary)?;
        if actual_sha256 != expected_sha256 {
            bail!(
                "healthy artifact SHA changed while copying; expected={expected_sha256} actual={actual_sha256}"
            );
        }
        fs::rename(&temporary, target)
            .with_context(|| format!("atomically replace {}", target.display()))?;
        set_private_permissions(target)?;
        File::open(parent)?.sync_all()?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

fn write_runtime_receipt(
    project: &Path,
    artifact: &Path,
    receipt: &RuntimeArtifactReceipt,
) -> Result<()> {
    write_json(&project.join("dist").join(LAST_RUNTIME_NAME), receipt)?;
    write_json(&runtime_sidecar(artifact), receipt)
}

fn runtime_sidecar(artifact: &Path) -> PathBuf {
    let name = artifact.file_name().unwrap_or_default().to_string_lossy();
    artifact.with_file_name(format!("{name}.runtime.json"))
}

fn update_publish_receipts(
    project: &Path,
    shadow_home: &Path,
    plugin_id: &str,
    sha256: &str,
    receipt: &RuntimeArtifactReceipt,
) -> Result<()> {
    update_receipt_object(
        &project.join("dist/last-published.json"),
        plugin_id,
        sha256,
        receipt,
    )?;
    update_receipt_object(
        &shadow_home.join("last-published.json"),
        plugin_id,
        sha256,
        receipt,
    )
}

fn update_receipt_object(
    path: &Path,
    plugin_id: &str,
    sha256: &str,
    runtime: &RuntimeArtifactReceipt,
) -> Result<()> {
    if !path.is_file() {
        return Ok(());
    }
    let mut value: Value = serde_json::from_slice(&fs::read(path)?)
        .with_context(|| format!("parse publish receipt {}", path.display()))?;
    if value.get("pluginId").and_then(Value::as_str) != Some(plugin_id)
        || value.get("sha256").and_then(Value::as_str) != Some(sha256)
    {
        return Ok(());
    }
    let object = value
        .as_object_mut()
        .context("publish receipt is not a JSON object")?;
    object.insert("runtimeStatus".to_owned(), json!(runtime.status));
    object.insert("runtimeProven".to_owned(), json!(runtime.runtime_proven));
    insert_optional(object, "runtimeGeneration", runtime.generation.as_deref());
    insert_optional(
        object,
        "runtimeOperationId",
        runtime.operation_id.as_deref(),
    );
    insert_optional(object, "runtimeError", runtime.error.as_deref());
    object.insert(
        "runtimeCheckedAtEpochMs".to_owned(),
        json!(runtime.checked_at_epoch_ms),
    );
    write_json(path, &value)
}

fn insert_optional(object: &mut Map<String, Value>, key: &str, value: Option<&str>) {
    object.insert(
        key.to_owned(),
        value.map_or(Value::Null, |value| Value::String(value.to_owned())),
    );
}

fn read_publish_receipt(project: &Path) -> Result<Option<(String, Value)>> {
    let path = project.join("dist/last-published.json");
    if !path.is_file() {
        return Ok(None);
    }
    let value: Value = serde_json::from_slice(&fs::read(&path)?)
        .with_context(|| format!("parse {}", path.display()))?;
    let Some(sha256) = value.get("sha256").and_then(Value::as_str) else {
        return Ok(None);
    };
    Ok(Some((sha256.to_owned(), value)))
}

fn current_active_view(project: &Path, _plugin_id: &str) -> Result<Option<ArtifactView>> {
    let path = project.join("dist").join(ACTIVE_ARTIFACT_NAME);
    if !path.is_file() {
        return Ok(None);
    }
    Ok(Some(ArtifactView {
        path: format!("dist/{ACTIVE_ARTIFACT_NAME}"),
        sha256: sha256_file(&path)?,
    }))
}

fn read_runtime_receipt(path: &Path) -> Option<RuntimeArtifactReceipt> {
    fs::read(path)
        .ok()
        .and_then(|bytes| serde_json::from_slice(&bytes).ok())
}

fn display_project_path(project: &Path, path: &Path) -> String {
    path.strip_prefix(project)
        .unwrap_or(path)
        .to_string_lossy()
        .into_owned()
}

fn write_json<T: Serialize + ?Sized>(path: &Path, value: &T) -> Result<()> {
    let mut bytes = serde_json::to_vec_pretty(value)?;
    bytes.push(b'\n');
    write_atomic(path, &bytes)
}

fn safe_segment(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 160
        && value.chars().all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | '-')
        })
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
    use super::{ACTIVE_ARTIFACT_NAME, reconcile_at};
    use crate::fsutil::sha256_file;
    use serde_json::{Value, json};
    use std::fs;

    #[test]
    fn unregistered_project_reconciliation_is_an_idempotent_noop() {
        let temp = tempfile::tempdir().unwrap();
        let project = temp.path().join("project");
        let shadow_home = temp.path().join("shadow");
        fs::create_dir_all(shadow_home.join("reports")).unwrap();
        fs::write(
            shadow_home.join("reports/registry.json"),
            br#"{"schemaVersion":3,"revision":1,"plugins":[]}"#,
        )
        .unwrap();

        reconcile_at(
            &project,
            &shadow_home,
            "com.termux.shadow.first.release",
            None,
        )
        .unwrap();
        assert!(!project.exists());
    }

    #[test]
    fn failed_publish_stays_explicit_while_active_points_to_the_last_healthy_bundle() {
        let temp = tempfile::tempdir().unwrap();
        let project = temp.path().join("project");
        let shadow_home = temp.path().join("shadow");
        let dist = project.join("dist");
        fs::create_dir_all(&dist).unwrap();

        let healthy_bytes = b"healthy bundle";
        let failed_bytes = b"failed bundle";
        let healthy_sha = {
            let path = temp.path().join("healthy.tmp");
            fs::write(&path, healthy_bytes).unwrap();
            sha256_file(&path).unwrap()
        };
        let failed_artifact = dist.join("notes-2.1.4.shadowpkg");
        fs::write(&failed_artifact, failed_bytes).unwrap();
        let failed_sha = sha256_file(&failed_artifact).unwrap();

        let repository = shadow_home.join("repository/plugins/com.termux.shadow.notes/13-good");
        fs::create_dir_all(&repository).unwrap();
        fs::write(repository.join("bundle.shadowpkg"), healthy_bytes).unwrap();
        fs::create_dir_all(shadow_home.join("reports")).unwrap();
        fs::write(
            shadow_home.join("reports/registry.json"),
            serde_json::to_vec(&json!({
                "schemaVersion": 3,
                "revision": 7,
                "plugins": [{
                    "pluginId": "com.termux.shadow.notes",
                    "enabled": true,
                    "activeGeneration": "13-good",
                    "previousGeneration": null,
                    "candidateGeneration": null,
                    "activatingGeneration": null,
                    "versions": [{
                        "generation": "13-good",
                        "bundleSha256": healthy_sha,
                        "state": "HEALTHY",
                        "manifest": {"versionCode": 13, "versionName": "2.1.1"},
                        "lastError": null,
                        "runtimeHealthProtocolVersion": 1,
                        "runtimeStableAt": 1600,
                        "lastHealthyProcessPid": 1234
                    }, {
                        "generation": "16-bad",
                        "bundleSha256": failed_sha,
                        "state": "ROLLED_BACK",
                        "manifest": {"versionCode": 16, "versionName": "2.1.4"},
                        "lastError": "java.lang.IllegalStateException: boom",
                        "runtimeHealthProtocolVersion": 0,
                        "runtimeStableAt": 0,
                        "lastHealthyProcessPid": 0
                    }]
                }]
            }))
            .unwrap(),
        )
        .unwrap();
        let published = json!({
            "schemaVersion": 2,
            "pluginId": "com.termux.shadow.notes",
            "versionCode": 16,
            "versionName": "2.1.4",
            "sha256": failed_sha,
            "fileName": "com.termux.shadow.notes-2.1.4.shadowpkg",
            "runtimeStatus": "UNPROVEN",
            "runtimeProven": false
        });
        fs::write(
            dist.join("last-published.json"),
            serde_json::to_vec(&published).unwrap(),
        )
        .unwrap();
        fs::write(
            shadow_home.join("last-published.json"),
            serde_json::to_vec(&published).unwrap(),
        )
        .unwrap();

        reconcile_at(&project, &shadow_home, "com.termux.shadow.notes", None).unwrap();

        let active = dist.join(ACTIVE_ARTIFACT_NAME);
        assert_eq!(sha256_file(&active).unwrap(), healthy_sha);
        let healthy: Value =
            serde_json::from_slice(&fs::read(dist.join("last-healthy.json")).unwrap()).unwrap();
        assert_eq!(healthy["generation"], "13-good");
        assert_eq!(healthy["runtimeProven"], true);
        let last_published: Value =
            serde_json::from_slice(&fs::read(dist.join("last-published.json")).unwrap()).unwrap();
        assert_eq!(last_published["runtimeStatus"], "ACTIVATION_FAILED");
        assert_eq!(last_published["runtimeProven"], false);
        let sidecar: Value = serde_json::from_slice(
            &fs::read(dist.join("notes-2.1.4.shadowpkg.runtime.json")).unwrap(),
        )
        .unwrap();
        assert_eq!(sidecar["status"], "ACTIVATION_FAILED");
        assert_eq!(sidecar["sha256"], failed_sha);
    }
}
