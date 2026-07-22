use std::fs;

use anyhow::Result;
use serde::Serialize;
use serde_json::json;

use crate::cache;
use crate::cli::ContextArgs;
use crate::config::PluginConfig;
use crate::context::{AppContext, PROJECT_CONFIG};
use crate::history;
use crate::status::{
    HealthReport, PluginRecord, PublishReceipt, RegistryReport, VersionRecord, find_receipt,
};
use crate::worker::{self, WorkerInfo};

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ContextCapsule {
    protocol_version: u32,
    changed: bool,
    registry_revision: u64,
    cli_version: &'static str,
    worker: WorkerInfo,
    host_status: String,
    project: Option<String>,
    plugin_id: Option<String>,
    source_fingerprint: Option<String>,
    active_fingerprint: Option<String>,
    dirty_since_active: Option<bool>,
    next_version_code: Option<u64>,
    last_published: Option<PublishReceipt>,
    currently_active: Option<GenerationSummary>,
    previous_healthy: Option<GenerationSummary>,
    pending_candidate: Option<GenerationSummary>,
    activating_generation: Option<GenerationSummary>,
    matching_cached_artifact: Option<ArtifactSummary>,
    last_worker_operation_id: Option<String>,
    last_failure_code: Option<String>,
    recommended_action: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct GenerationSummary {
    generation: String,
    sha256: String,
    version_code: Option<u64>,
    version_name: Option<String>,
    state: String,
    runtime_proven: bool,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ArtifactSummary {
    path: String,
    sha256: String,
}

pub fn run(context: &AppContext, args: ContextArgs) -> Result<()> {
    let health = read_optional::<HealthReport>(&context.shadow_home.join("reports/health.json"));
    let registry =
        read_optional::<RegistryReport>(&context.shadow_home.join("reports/registry.json"))
            .unwrap_or_default();
    let revision = health
        .as_ref()
        .and_then(|health| health.registry_revision)
        .unwrap_or(registry.revision);
    if args.since_revision.is_some_and(|known| known >= revision) {
        let value = unchanged_context(revision);
        if context.json {
            if context.verbose {
                println!("{}", serde_json::to_string_pretty(&value)?);
            } else {
                println!("{}", serde_json::to_string(&value)?);
            }
        } else {
            println!("context: unchanged at registry revision {revision}");
        }
        return Ok(());
    }

    let project = context.project_if_present();
    let config = project
        .as_ref()
        .and_then(|path| PluginConfig::load(&path.join(PROJECT_CONFIG)).ok());
    let plugin = config.as_ref().and_then(|config| {
        registry
            .plugins
            .iter()
            .find(|plugin| plugin.plugin_id == config.plugin_id)
    });
    let active = plugin.and_then(|plugin| generation(plugin, plugin.active_generation.as_deref()));
    let candidate =
        plugin.and_then(|plugin| generation(plugin, plugin.candidate_generation.as_deref()));
    let activating =
        plugin.and_then(|plugin| generation(plugin, plugin.activating_generation.as_deref()));
    let previous = plugin
        .and_then(|plugin| generation(plugin, plugin.previous_generation.as_deref()))
        .filter(|version| version.runtime_proven);
    let highest = plugin.and_then(|plugin| {
        plugin
            .versions
            .iter()
            .filter_map(|version| version.manifest.as_ref()?.version_code)
            .max()
    });
    let next_version_code = config.as_ref().map(|config| {
        highest
            .map(|version| version.saturating_add(1))
            .unwrap_or(config.default_version_code)
    });
    let last_published = match (&project, &config) {
        (Some(project), Some(config)) => {
            find_receipt(project, &context.shadow_home, &config.plugin_id)?
        }
        _ => None,
    };
    let last_operation = match &config {
        Some(config) => history::latest(&context.shadow_home, &config.plugin_id)?,
        None => None,
    };

    let mut source_fingerprint = None;
    let mut active_fingerprint = None;
    let mut dirty_since_active = None;
    let mut matching_cached_artifact = None;
    if let (Some(project), Some(config), Ok(environment)) =
        (&project, &config, context.build_environment())
    {
        let source = cache::source_fingerprint(project, &environment)?;
        source_fingerprint = Some(source.clone());
        if let Some(active) = &active {
            active_fingerprint = history::latest_for_artifact(
                &context.shadow_home,
                &config.plugin_id,
                &active.sha256,
            )?
            .map(|record| record.source_fingerprint);
            let cache_hit = if let (Some(code), Some(name)) =
                (active.version_code, active.version_name.as_deref())
            {
                let input = cache::input_fingerprint(project, &environment, code, name)?;
                cache::lookup(project, &input, code, name, true)?
                    .filter(|hit| hit.sha256 == active.sha256)
            } else {
                None
            };
            matching_cached_artifact = cache_hit.map(|hit| ArtifactSummary {
                path: hit.artifact.display().to_string(),
                sha256: hit.sha256,
            });
            dirty_since_active = Some(
                active_fingerprint.as_deref() != Some(source.as_str())
                    && matching_cached_artifact.is_none(),
            );
        } else {
            dirty_since_active = Some(true);
        }
    }
    let recommended_action = if project.is_none() {
        "SELECT_OR_CREATE_PROJECT"
    } else if plugin.is_none() || dirty_since_active == Some(true) {
        "DEPLOY"
    } else if activating.is_some() {
        "WAIT_FOR_ACTIVATION"
    } else if candidate.is_some() || active.as_ref().is_some_and(|value| !value.runtime_proven) {
        "RUN"
    } else {
        "NO_CHANGES"
    };
    let capsule = ContextCapsule {
        protocol_version: 1,
        changed: true,
        registry_revision: revision,
        cli_version: env!("CARGO_PKG_VERSION"),
        worker: worker::inspect(context),
        host_status: health
            .and_then(|health| health.status)
            .unwrap_or_else(|| "UNAVAILABLE".to_owned()),
        project: project.as_ref().map(|path| path.display().to_string()),
        plugin_id: config.as_ref().map(|config| config.plugin_id.clone()),
        source_fingerprint,
        active_fingerprint,
        dirty_since_active,
        next_version_code,
        last_published,
        currently_active: active,
        previous_healthy: previous,
        pending_candidate: candidate,
        activating_generation: activating,
        matching_cached_artifact,
        last_worker_operation_id: last_operation
            .as_ref()
            .map(|operation| operation.operation_id.clone()),
        last_failure_code: last_operation.and_then(|operation| operation.error_code),
        recommended_action: recommended_action.to_owned(),
    };
    if context.json {
        let mut value = serde_json::to_value(&capsule)?;
        if context.verbose {
            println!("{}", serde_json::to_string_pretty(&value)?);
        } else {
            compact_context_value(&mut value);
            println!("{}", serde_json::to_string(&value)?);
        }
    } else {
        println!("context: {}", capsule.recommended_action);
        println!("  project={}", capsule.project.as_deref().unwrap_or("-"));
        println!(
            "  pluginId={} revision={}",
            capsule.plugin_id.as_deref().unwrap_or("-"),
            capsule.registry_revision
        );
        println!(
            "  dirtySinceActive={} nextVersionCode={}",
            capsule
                .dirty_since_active
                .map(|value| value.to_string())
                .as_deref()
                .unwrap_or("unknown"),
            capsule
                .next_version_code
                .map(|value| value.to_string())
                .as_deref()
                .unwrap_or("-")
        );
        println!(
            "  worker={} daemon={}",
            capsule.worker.status, capsule.worker.gradle_daemon
        );
    }
    Ok(())
}

fn compact_context_value(value: &mut serde_json::Value) {
    let Some(object) = value.as_object_mut() else {
        return;
    };
    for key in [
        "protocolVersion",
        "cliVersion",
        "project",
        "activeFingerprint",
        "lastWorkerOperationId",
    ] {
        object.remove(key);
    }
    if let Some(worker) = object.get("worker").and_then(serde_json::Value::as_object) {
        let summary = json!({
            "status": worker.get("status"),
            "pid": worker.get("pid"),
            "reused": worker
                .get("requestsServed")
                .and_then(serde_json::Value::as_u64)
                .is_some_and(|count| count > 0),
            "daemon": worker.get("gradleDaemon"),
        });
        object.insert("worker".to_owned(), summary);
    }
    if let Some(receipt) = object
        .get_mut("lastPublished")
        .and_then(serde_json::Value::as_object_mut)
    {
        for key in ["pluginId", "fileName", "resourcePackageId"] {
            receipt.remove(key);
        }
    }
    remove_nulls(value);
}

fn remove_nulls(value: &mut serde_json::Value) {
    match value {
        serde_json::Value::Object(object) => {
            object.retain(|_, value| !value.is_null());
            for value in object.values_mut() {
                remove_nulls(value);
            }
        }
        serde_json::Value::Array(items) => {
            for item in items {
                remove_nulls(item);
            }
        }
        _ => {}
    }
}

fn unchanged_context(revision: u64) -> serde_json::Value {
    json!({
        "protocolVersion": 1,
        "changed": false,
        "registryRevision": revision,
    })
}

fn generation(plugin: &PluginRecord, generation: Option<&str>) -> Option<GenerationSummary> {
    let generation = generation?;
    let version = plugin
        .versions
        .iter()
        .find(|version| version.generation == generation)?;
    Some(summary(version))
}

fn summary(version: &VersionRecord) -> GenerationSummary {
    GenerationSummary {
        generation: version.generation.clone(),
        sha256: version.bundle_sha256.clone(),
        version_code: version
            .manifest
            .as_ref()
            .and_then(|manifest| manifest.version_code),
        version_name: version
            .manifest
            .as_ref()
            .and_then(|manifest| manifest.version_name.clone()),
        state: version.state.clone(),
        runtime_proven: version.runtime_health_protocol_version >= 1
            && version.runtime_stable_at > 0
            && version.last_healthy_process_pid > 0,
    }
}

fn read_optional<T: serde::de::DeserializeOwned>(path: &std::path::Path) -> Option<T> {
    fs::read(path)
        .ok()
        .and_then(|bytes| serde_json::from_slice(&bytes).ok())
}

#[cfg(test)]
mod tests {
    use super::compact_context_value;
    use super::summary;
    use super::unchanged_context;
    use crate::status::VersionRecord;
    use serde_json::json;

    #[test]
    fn runtime_proof_requires_protocol_stability_and_pid() {
        let version = VersionRecord {
            generation: "1-a".into(),
            bundle_sha256: "a".into(),
            state: "HEALTHY".into(),
            trust_level: None,
            manifest: None,
            last_error: None,
            total_launch_attempts: 1,
            total_launch_failures: 0,
            consecutive_launch_failures: 0,
            last_launch_success_at: 1,
            runtime_health_protocol_version: 1,
            runtime_ready_at: 1,
            runtime_stable_at: 2,
            last_healthy_process_pid: 42,
            last_healthy_process_name: None,
        };
        assert!(summary(&version).runtime_proven);
    }

    #[test]
    fn unchanged_context_contains_only_the_revision_envelope() {
        let value = unchanged_context(258);
        let object = value.as_object().unwrap();
        assert_eq!(object.len(), 3);
        assert_eq!(object["changed"], false);
        assert_eq!(object["registryRevision"], 258);
        assert!(serde_json::to_vec(&value).unwrap().len() < 80);
    }

    #[test]
    fn compact_context_reduces_worker_and_null_metadata() {
        let mut value = json!({
            "protocolVersion": 1,
            "cliVersion": env!("CARGO_PKG_VERSION"),
            "project": "/private/project",
            "pluginId": "com.termux.shadow.notes",
            "activeFingerprint": null,
            "lastWorkerOperationId": "op-old",
            "worker": {
                "status": "READY",
                "pid": 123,
                "requestsServed": 8,
                "gradleDaemon": "WARM",
                "socket": "/private/socket",
                "binarySha256": "abcdef"
            },
            "lastPublished": {
                "pluginId": "com.termux.shadow.notes",
                "sha256": "abcdef",
                "fileName": "notes.shadowpkg",
                "versionCode": 16,
                "versionName": "2.1.5",
                "resourcePackageId": "0x7B"
            },
            "pendingCandidate": null,
            "recommendedAction": "NO_CHANGES"
        });
        compact_context_value(&mut value);
        assert_eq!(value["worker"]["reused"], true);
        assert_eq!(value["worker"]["daemon"], "WARM");
        assert_eq!(value["lastPublished"]["sha256"], "abcdef");
        for omitted in [
            "protocolVersion",
            "cliVersion",
            "project",
            "activeFingerprint",
            "lastWorkerOperationId",
            "pendingCandidate",
        ] {
            assert!(value.get(omitted).is_none(), "unexpected {omitted}");
        }
        assert!(value["worker"].get("socket").is_none());
        assert!(value["lastPublished"].get("pluginId").is_none());
    }
}
