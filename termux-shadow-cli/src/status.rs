use std::fs;
use std::path::Path;
use std::thread;
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};

use crate::cache;
use crate::cli::StatusArgs;
use crate::config::PluginConfig;
use crate::context::AppContext;
use crate::history;

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HealthReport {
    #[serde(default)]
    pub schema_version: u32,
    pub status: Option<String>,
    pub registry_revision: Option<u64>,
    pub ingress_mode: Option<String>,
    pub package_schema_version: Option<u32>,
    pub plugins: Option<u64>,
    pub enabled_plugins: Option<u64>,
    pub versions: Option<u64>,
    pub manager_sha256: Option<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RegistryReport {
    #[serde(default)]
    pub schema_version: u32,
    #[serde(default)]
    pub revision: u64,
    #[serde(default)]
    pub plugins: Vec<PluginRecord>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginRecord {
    pub plugin_id: String,
    #[serde(default = "default_true")]
    pub enabled: bool,
    pub active_generation: Option<String>,
    pub previous_generation: Option<String>,
    pub candidate_generation: Option<String>,
    pub activating_generation: Option<String>,
    #[serde(default)]
    pub removal_requested: bool,
    #[serde(default)]
    pub versions: Vec<VersionRecord>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionRecord {
    pub generation: String,
    pub bundle_sha256: String,
    pub state: String,
    pub trust_level: Option<String>,
    pub manifest: Option<Manifest>,
    pub last_error: Option<String>,
    #[serde(default)]
    pub total_launch_attempts: u64,
    #[serde(default)]
    pub total_launch_failures: u64,
    #[serde(default)]
    pub consecutive_launch_failures: u64,
    #[serde(default)]
    pub last_launch_success_at: u64,
    #[serde(default)]
    pub runtime_health_protocol_version: u32,
    #[serde(default)]
    pub runtime_ready_at: u64,
    #[serde(default)]
    pub runtime_stable_at: u64,
    #[serde(default)]
    pub last_healthy_process_pid: u32,
    pub last_healthy_process_name: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Manifest {
    pub version_code: Option<u64>,
    pub version_name: Option<String>,
    pub display_name: Option<String>,
    pub resource_package_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PublishReceipt {
    pub plugin_id: String,
    pub sha256: String,
    pub file_name: String,
    pub version_code: Option<u64>,
    pub version_name: Option<String>,
    pub resource_package_id: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct StatusOutput {
    shadow_home: String,
    current_plugin_id: Option<String>,
    health: Option<HealthReport>,
    last_published: Option<PublishReceipt>,
    currently_active: Option<ActiveVersionSummary>,
    runtime_artifacts: Option<crate::runtime_artifacts::RuntimeArtifactView>,
    plugins: Vec<PluginRecord>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct ActiveVersionSummary {
    generation: String,
    sha256: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    version_code: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    version_name: Option<String>,
    state: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    trust_level: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct RawStatusOutput {
    health: Option<serde_json::Value>,
    registry: Option<serde_json::Value>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct CompactStatusOutput {
    ok: bool,
    status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    plugin_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    active: Option<ActiveVersionSummary>,
    #[serde(skip_serializing_if = "Option::is_none")]
    candidate: Option<ActiveVersionSummary>,
    #[serde(skip_serializing_if = "Option::is_none")]
    activating: Option<ActiveVersionSummary>,
    #[serde(skip_serializing_if = "Option::is_none")]
    previous_healthy: Option<ActiveVersionSummary>,
    #[serde(skip_serializing_if = "Option::is_none")]
    next_version_code: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    source_fingerprint: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    dirty_since_active: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    cached_artifact: Option<CachedArtifact>,
    #[serde(skip_serializing_if = "Option::is_none")]
    runtime_artifacts: Option<crate::runtime_artifacts::RuntimeArtifactCompactView>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct CachedArtifact {
    path: String,
    sha256: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LaunchReport {
    pub plugin_id: String,
    pub generation: String,
    pub operation_id: String,
    pub status: String,
    pub updated_at: u64,
    pub error: Option<String>,
}

pub fn run(context: &AppContext, args: StatusArgs) -> Result<()> {
    let project_config = context
        .project_if_present()
        .and_then(|project| PluginConfig::load(&project.join("shadow-plugin.properties")).ok());
    let current_plugin_id = project_config
        .as_ref()
        .map(|config| config.plugin_id.as_str());
    let shadow_home = args
        .shadow_home
        .clone()
        .unwrap_or_else(|| context.shadow_home.clone());

    if args.wait {
        let config = project_config
            .as_ref()
            .context("status --wait requires a current plugin project")?;
        let project = context.project()?;
        let receipt = find_receipt(&project, &shadow_home, &config.plugin_id)?
            .with_context(|| format!("no publish receipt found for {}", config.plugin_id))?;
        wait_for_receipt(&shadow_home, &receipt, Duration::from_secs(args.timeout))?;
        if !context.json {
            println!("Registration confirmed: {}\n", receipt.sha256);
        }
    }

    if args.raw {
        let health_path = shadow_home.join("reports/health.json");
        let registry_path = shadow_home.join("reports/registry.json");
        if context.json {
            println!(
                "{}",
                serde_json::to_string_pretty(&RawStatusOutput {
                    health: read_optional_json(&health_path)?,
                    registry: read_optional_json(&registry_path)?,
                })?
            );
            return Ok(());
        }
        if health_path.is_file() {
            let text = fs::read_to_string(&health_path)?;
            print!("{text}");
            if !text.ends_with('\n') {
                println!();
            }
        }
        if registry_path.is_file() {
            print!("{}", fs::read_to_string(&registry_path)?);
        }
        return Ok(());
    }

    let runtime_artifacts = if let Some(plugin_id) = current_plugin_id
        && args.shadow_home.is_none()
    {
        crate::runtime_artifacts::reconcile_project(context, plugin_id)?;
        crate::runtime_artifacts::view(context, plugin_id)?
    } else {
        None
    };

    let health = read_optional_json::<HealthReport>(&shadow_home.join("reports/health.json"))?;
    let registry =
        read_optional_json::<RegistryReport>(&shadow_home.join("reports/registry.json"))?
            .unwrap_or_default();
    let last_published = match (context.project_if_present(), current_plugin_id) {
        (Some(project), Some(plugin_id)) => find_receipt(&project, &shadow_home, plugin_id)?,
        _ => None,
    };
    let currently_active = current_plugin_id.and_then(|plugin_id| {
        registry
            .plugins
            .iter()
            .find(|plugin| plugin.plugin_id == plugin_id)
            .and_then(active_version_summary)
    });
    let compact_output = should_emit_compact(args.compact, context.json, args.all, args.history);
    if compact_output {
        let current_plugin = current_plugin_id.and_then(|plugin_id| {
            registry
                .plugins
                .iter()
                .find(|plugin| plugin.plugin_id == plugin_id)
        });
        let candidate = current_plugin
            .and_then(|plugin| version_summary(plugin, plugin.candidate_generation.as_deref()));
        let activating = current_plugin
            .and_then(|plugin| version_summary(plugin, plugin.activating_generation.as_deref()));
        let previous_healthy = current_plugin.and_then(|plugin| {
            let version = plugin.versions.iter().find(|version| {
                Some(version.generation.as_str()) == plugin.previous_generation.as_deref()
            })?;
            (version.runtime_health_protocol_version >= 1
                && version.runtime_stable_at > 0
                && version.last_healthy_process_pid > 0)
                .then(|| version_summary(plugin, Some(&version.generation)))
                .flatten()
        });
        let next_version_code = project_config.as_ref().map(|config| {
            current_plugin
                .and_then(|plugin| {
                    plugin
                        .versions
                        .iter()
                        .filter_map(|version| version.manifest.as_ref()?.version_code)
                        .max()
                })
                .map(|version| version.saturating_add(1))
                .unwrap_or(config.default_version_code)
        });
        let (source_fingerprint, dirty_since_active, cached_artifact) =
            compact_fingerprint_state(context, project_config.as_ref(), current_plugin)?;
        let compact = CompactStatusOutput {
            ok: true,
            status: health
                .as_ref()
                .and_then(|health| health.status.clone())
                .unwrap_or_else(|| "UNAVAILABLE".to_owned()),
            plugin_id: current_plugin_id.map(str::to_owned),
            active: currently_active,
            candidate,
            activating,
            previous_healthy,
            next_version_code,
            source_fingerprint,
            dirty_since_active,
            cached_artifact,
            runtime_artifacts: runtime_artifacts.as_ref().map(|view| view.compact()),
        };
        if context.json {
            if context.verbose {
                println!("{}", serde_json::to_string_pretty(&compact)?);
            } else {
                println!("{}", serde_json::to_string(&compact)?);
            }
        } else {
            print_compact(&compact);
        }
        return Ok(());
    }
    let plugins = registry
        .plugins
        .into_iter()
        .filter(|plugin| args.all || Some(plugin.plugin_id.as_str()) == current_plugin_id)
        .collect::<Vec<_>>();

    let output = StatusOutput {
        shadow_home: shadow_home.display().to_string(),
        current_plugin_id: current_plugin_id.map(str::to_owned),
        health,
        last_published,
        currently_active,
        runtime_artifacts,
        plugins,
    };
    if context.json {
        println!("{}", serde_json::to_string_pretty(&output)?);
    } else {
        print_human(&output);
    }
    Ok(())
}

fn should_emit_compact(explicit: bool, json: bool, all: bool, history: bool) -> bool {
    explicit || (json && !all && !history)
}

fn compact_fingerprint_state(
    context: &AppContext,
    config: Option<&PluginConfig>,
    plugin: Option<&PluginRecord>,
) -> Result<(Option<String>, Option<bool>, Option<CachedArtifact>)> {
    let (Some(config), Some(project), Some(environment)) = (
        config,
        context.project_if_present(),
        context.build_environment().ok(),
    ) else {
        return Ok((None, None, None));
    };
    let source_fingerprint = cache::source_fingerprint(&project, &environment)?;
    let Some(plugin) = plugin else {
        return Ok((Some(source_fingerprint), Some(true), None));
    };
    let Some(active) = plugin.active_generation.as_deref().and_then(|generation| {
        plugin
            .versions
            .iter()
            .find(|version| version.generation == generation)
    }) else {
        return Ok((Some(source_fingerprint), Some(true), None));
    };
    let history_matches =
        history::latest_for_source(&context.shadow_home, &config.plugin_id, &source_fingerprint)?
            .and_then(|record| record.artifact_sha256)
            .as_deref()
            == Some(active.bundle_sha256.as_str());
    let cached = if let Some(manifest) = &active.manifest
        && let (Some(version_code), Some(version_name)) =
            (manifest.version_code, manifest.version_name.as_deref())
    {
        let input = cache::input_fingerprint(&project, &environment, version_code, version_name)?;
        cache::lookup(&project, &input, version_code, version_name, true)?
    } else {
        None
    };
    let cache_matches = cached
        .as_ref()
        .is_some_and(|hit| hit.sha256 == active.bundle_sha256);
    let cached_artifact = cached.map(|hit| CachedArtifact {
        path: hit.artifact.display().to_string(),
        sha256: hit.sha256,
    });
    Ok((
        Some(source_fingerprint),
        Some(!(history_matches || cache_matches)),
        cached_artifact,
    ))
}

fn print_compact(output: &CompactStatusOutput) {
    println!(
        "status: {}  {}",
        output.status,
        output.plugin_id.as_deref().unwrap_or("no current plugin")
    );
    if let Some(active) = &output.active {
        println!(
            "  active={} {} ({})",
            active.generation,
            active.version_name.as_deref().unwrap_or("?"),
            active
                .version_code
                .map(|value| value.to_string())
                .as_deref()
                .unwrap_or("?"),
        );
    } else {
        println!("  active=-");
    }
    if let Some(candidate) = &output.candidate {
        println!("  candidate={}", candidate.generation);
    }
    if let Some(runtime) = &output.runtime_artifacts {
        if let Some(status) = &runtime.last_published_status {
            println!(
                "  lastPublishedRuntime={} proven={}",
                status,
                runtime
                    .last_published_proven
                    .map(|value| value.to_string())
                    .as_deref()
                    .unwrap_or("unknown")
            );
        }
        if let Some(active) = &runtime.active_artifact {
            println!("  activeArtifact={} sha256={}", active.path, active.sha256);
        }
    }
    println!(
        "  dirtySinceActive={} nextVersionCode={}",
        output
            .dirty_since_active
            .map(|value| value.to_string())
            .as_deref()
            .unwrap_or("unknown"),
        output
            .next_version_code
            .map(|value| value.to_string())
            .as_deref()
            .unwrap_or("?")
    );
}

pub fn read_registry(shadow_home: &Path) -> Result<RegistryReport> {
    read_json(&shadow_home.join("reports/registry.json"))
}

pub fn find_receipt(
    project: &Path,
    shadow_home: &Path,
    plugin_id: &str,
) -> Result<Option<PublishReceipt>> {
    let local = project.join("dist/last-published.json");
    if local.is_file() {
        let receipt: PublishReceipt = read_json(&local)?;
        if receipt.plugin_id == plugin_id {
            return Ok(Some(receipt));
        }
    }
    let global = shadow_home.join("last-published.json");
    if global.is_file() {
        let receipt: PublishReceipt = read_json(&global)?;
        if receipt.plugin_id == plugin_id {
            return Ok(Some(receipt));
        }
    }
    Ok(None)
}

pub fn wait_for_receipt(
    shadow_home: &Path,
    receipt: &PublishReceipt,
    timeout: Duration,
) -> Result<()> {
    let started = Instant::now();
    loop {
        if let Ok(registry) = read_registry(shadow_home)
            && registry.plugins.iter().any(|plugin| {
                plugin.plugin_id == receipt.plugin_id
                    && plugin
                        .versions
                        .iter()
                        .any(|version| version.bundle_sha256 == receipt.sha256)
            })
        {
            return Ok(());
        }
        if let Some(error) = quarantine_error(shadow_home, &receipt.file_name)? {
            bail!("published package was quarantined: {error}");
        }
        if started.elapsed() >= timeout {
            bail!(
                "registration timed out after {}s for SHA {}; run `shadow-plugin refresh` and inspect reports/quarantine",
                timeout.as_secs(),
                receipt.sha256
            );
        }
        thread::sleep(Duration::from_millis(250));
    }
}

pub fn read_launch_report(shadow_home: &Path, plugin_id: &str) -> Result<Option<LaunchReport>> {
    let per_plugin = shadow_home
        .join("reports/launch")
        .join(format!("{plugin_id}.json"));
    if let Some(report) = read_optional_json::<LaunchReport>(&per_plugin)? {
        return Ok(Some(report));
    }
    let legacy = read_optional_json::<LaunchReport>(&shadow_home.join("reports/last-launch.json"))?;
    Ok(legacy.filter(|report| report.plugin_id == plugin_id))
}

fn quarantine_error(shadow_home: &Path, file_name: &str) -> Result<Option<String>> {
    let directory = shadow_home.join("quarantine");
    if !directory.is_dir() {
        return Ok(None);
    }
    for entry in fs::read_dir(&directory)? {
        let entry = entry?;
        let path = entry.path();
        if path.extension().and_then(|value| value.to_str()) != Some("json") {
            continue;
        }
        let value: serde_json::Value = match read_json(&path) {
            Ok(value) => value,
            Err(_) => continue,
        };
        let source = value
            .get("sourcePath")
            .or_else(|| value.get("fileName"))
            .and_then(|value| value.as_str())
            .unwrap_or_default();
        if source.contains(file_name) {
            return Ok(Some(
                value
                    .get("error")
                    .and_then(|value| value.as_str())
                    .unwrap_or("unknown host verification error")
                    .to_owned(),
            ));
        }
    }
    Ok(None)
}

fn print_human(output: &StatusOutput) {
    println!("Shadow platform");
    println!("  home: {}", output.shadow_home);
    if let Some(health) = &output.health {
        println!(
            "  status: {}",
            health.status.as_deref().unwrap_or("unknown")
        );
        println!(
            "  ingress: {} / schema {}",
            health.ingress_mode.as_deref().unwrap_or("unknown"),
            health
                .package_schema_version
                .map(|value| value.to_string())
                .as_deref()
                .unwrap_or("?")
        );
        println!(
            "  registry revision: {}",
            health
                .registry_revision
                .map(|value| value.to_string())
                .as_deref()
                .unwrap_or("?")
        );
    } else {
        println!("  status: unavailable (Host report not found)");
    }

    if let Some(plugin_id) = &output.current_plugin_id {
        println!("\nCurrent project");
        println!("  pluginId: {plugin_id}");
        if let Some(receipt) = &output.last_published {
            println!("  last published");
            println!("    SHA: {}", receipt.sha256);
            println!("    file: {}", receipt.file_name);
            println!(
                "    version: {} ({})",
                receipt.version_name.as_deref().unwrap_or("?"),
                receipt
                    .version_code
                    .map(|value| value.to_string())
                    .as_deref()
                    .unwrap_or("?")
            );
        } else {
            println!("  last published: none");
        }
        if let Some(active) = &output.currently_active {
            println!("  currently active");
            println!("    generation: {}", active.generation);
            println!("    SHA: {}", active.sha256);
            println!(
                "    version: {} ({})",
                active.version_name.as_deref().unwrap_or("?"),
                active
                    .version_code
                    .map(|value| value.to_string())
                    .as_deref()
                    .unwrap_or("?")
            );
            println!("    state: {}", active.state);
        } else {
            println!("  currently active: none");
        }
        if let Some(runtime) = &output.runtime_artifacts {
            if let Some(last) = &runtime.last_runtime {
                println!("  last published runtime proof");
                println!("    status: {}", last.status);
                println!("    proven: {}", last.runtime_proven);
                if let Some(error) = &last.error {
                    println!("    error: {error}");
                }
            }
            if let Some(active) = &runtime.active_artifact {
                println!("  safe active artifact");
                println!("    path: {}", active.path);
                println!("    SHA: {}", active.sha256);
            }
        }
    }

    println!("\nRegistered plugins");
    if output.plugins.is_empty() {
        println!("  (none matched)");
        return;
    }
    for plugin in &output.plugins {
        println!("  {}", plugin.plugin_id);
        println!("    enabled: {}", plugin.enabled);
        println!(
            "    active: {}",
            plugin.active_generation.as_deref().unwrap_or("-")
        );
        println!(
            "    candidate: {}",
            plugin.candidate_generation.as_deref().unwrap_or("-")
        );
        println!(
            "    activating: {}",
            plugin.activating_generation.as_deref().unwrap_or("-")
        );
        println!(
            "    previous: {}",
            plugin.previous_generation.as_deref().unwrap_or("-")
        );
        for version in &plugin.versions {
            let marker = if Some(version.generation.as_str()) == plugin.active_generation.as_deref()
            {
                "active"
            } else if Some(version.generation.as_str()) == plugin.activating_generation.as_deref() {
                "activating"
            } else if Some(version.generation.as_str()) == plugin.candidate_generation.as_deref() {
                "candidate"
            } else if Some(version.generation.as_str()) == plugin.previous_generation.as_deref() {
                "previous"
            } else {
                "retained"
            };
            println!(
                "    {}  {}  {}  {}",
                version.generation,
                version.state,
                version.trust_level.as_deref().unwrap_or("UNKNOWN"),
                marker
            );
            if let Some(error) = &version.last_error {
                println!("      error: {error}");
            }
        }
    }
}

fn active_version_summary(plugin: &PluginRecord) -> Option<ActiveVersionSummary> {
    version_summary(plugin, plugin.active_generation.as_deref())
}

fn version_summary(
    plugin: &PluginRecord,
    generation: Option<&str>,
) -> Option<ActiveVersionSummary> {
    let active_generation = generation?;
    let version = plugin
        .versions
        .iter()
        .find(|version| version.generation == active_generation)?;
    Some(ActiveVersionSummary {
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
        trust_level: version.trust_level.clone(),
    })
}

fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<T> {
    let bytes = fs::read(path).with_context(|| format!("read {}", path.display()))?;
    serde_json::from_slice(&bytes).with_context(|| format!("parse {}", path.display()))
}

fn read_optional_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<Option<T>> {
    if !path.is_file() {
        return Ok(None);
    }
    read_json(path).map(Some)
}

fn default_true() -> bool {
    true
}

#[cfg(test)]
mod tests {
    use super::should_emit_compact;
    use super::{
        Manifest, PluginRecord, PublishReceipt, VersionRecord, active_version_summary,
        read_launch_report, wait_for_receipt,
    };
    use std::fs;
    use std::time::Duration;

    #[test]
    fn waits_for_exact_receipt_sha() {
        let temp = tempfile::tempdir().unwrap();
        let reports = temp.path().join("reports");
        fs::create_dir_all(&reports).unwrap();
        fs::write(
            reports.join("registry.json"),
            r#"{
              "schemaVersion": 1,
              "revision": 4,
              "plugins": [{
                "pluginId": "com.termux.shadow.notes",
                "versions": [{
                  "generation": "1-0123456789abcdef",
                  "bundleSha256": "0123456789abcdef",
                  "state": "INSTALLED"
                }]
              }]
            }"#,
        )
        .unwrap();
        let receipt = PublishReceipt {
            plugin_id: "com.termux.shadow.notes".into(),
            sha256: "0123456789abcdef".into(),
            file_name: "notes.shadowpkg".into(),
            version_code: Some(1),
            version_name: Some("1.0.0".into()),
            resource_package_id: Some("0x7B".into()),
        };
        wait_for_receipt(temp.path(), &receipt, Duration::from_millis(10)).unwrap();
    }

    #[test]
    fn does_not_accept_a_matching_sha_owned_by_another_plugin() {
        let temp = tempfile::tempdir().unwrap();
        let reports = temp.path().join("reports");
        fs::create_dir_all(&reports).unwrap();
        fs::write(
            reports.join("registry.json"),
            r#"{
              "schemaVersion": 1,
              "revision": 4,
              "plugins": [{
                "pluginId": "com.termux.shadow.other",
                "versions": [{
                  "generation": "1-0123456789abcdef",
                  "bundleSha256": "0123456789abcdef",
                  "state": "INSTALLED"
                }]
              }]
            }"#,
        )
        .unwrap();
        let receipt = PublishReceipt {
            plugin_id: "com.termux.shadow.notes".into(),
            sha256: "0123456789abcdef".into(),
            file_name: "notes.shadowpkg".into(),
            version_code: Some(1),
            version_name: Some("1.0.0".into()),
            resource_package_id: Some("0x7B".into()),
        };
        assert!(wait_for_receipt(temp.path(), &receipt, Duration::ZERO).is_err());
    }

    #[test]
    fn active_summary_is_derived_from_the_active_pointer() {
        let plugin = PluginRecord {
            plugin_id: "com.termux.shadow.basic".into(),
            enabled: true,
            active_generation: Some("1-active".into()),
            previous_generation: Some("2-last-published".into()),
            candidate_generation: None,
            activating_generation: None,
            removal_requested: false,
            versions: vec![
                VersionRecord {
                    generation: "2-last-published".into(),
                    bundle_sha256: "published".into(),
                    state: "SUPERSEDED".into(),
                    trust_level: Some("INTEGRITY_VERIFIED".into()),
                    manifest: Some(Manifest {
                        version_code: Some(2),
                        version_name: Some("2.0.0".into()),
                        display_name: None,
                        resource_package_id: None,
                    }),
                    last_error: None,
                    total_launch_attempts: 0,
                    total_launch_failures: 0,
                    consecutive_launch_failures: 0,
                    last_launch_success_at: 0,
                    runtime_health_protocol_version: 0,
                    runtime_ready_at: 0,
                    runtime_stable_at: 0,
                    last_healthy_process_pid: 0,
                    last_healthy_process_name: None,
                },
                VersionRecord {
                    generation: "1-active".into(),
                    bundle_sha256: "active".into(),
                    state: "HEALTHY".into(),
                    trust_level: Some("INTEGRITY_VERIFIED".into()),
                    manifest: Some(Manifest {
                        version_code: Some(1),
                        version_name: Some("1.0.0".into()),
                        display_name: None,
                        resource_package_id: None,
                    }),
                    last_error: None,
                    total_launch_attempts: 0,
                    total_launch_failures: 0,
                    consecutive_launch_failures: 0,
                    last_launch_success_at: 0,
                    runtime_health_protocol_version: 1,
                    runtime_ready_at: 1,
                    runtime_stable_at: 2,
                    last_healthy_process_pid: 42,
                    last_healthy_process_name: Some("com.termux:plugin".into()),
                },
            ],
        };
        let active = active_version_summary(&plugin).unwrap();
        assert_eq!(active.sha256, "active");
        assert_eq!(active.version_code, Some(1));
    }

    #[test]
    fn launch_reports_are_isolated_per_plugin() {
        let temp = tempfile::tempdir().unwrap();
        let launch_reports = temp.path().join("reports/launch");
        fs::create_dir_all(&launch_reports).unwrap();
        fs::write(
            launch_reports.join("com.termux.shadow.notes.json"),
            r#"{
              "pluginId":"com.termux.shadow.notes",
              "generation":"13-notes",
              "operationId":"notes-operation",
              "status":"FIRST_FRAME_READY",
              "updatedAt":10,
              "error":null
            }"#,
        )
        .unwrap();
        fs::write(
            temp.path().join("reports/last-launch.json"),
            r#"{
              "pluginId":"com.termux.shadow.other",
              "generation":"9-other",
              "operationId":"other-operation",
              "status":"HEALTHY",
              "updatedAt":11,
              "error":null
            }"#,
        )
        .unwrap();

        let report = read_launch_report(temp.path(), "com.termux.shadow.notes")
            .unwrap()
            .unwrap();
        assert_eq!(report.operation_id, "notes-operation");
        assert_eq!(report.status, "FIRST_FRAME_READY");
        assert!(
            read_launch_report(temp.path(), "com.termux.shadow.missing")
                .unwrap()
                .is_none()
        );
    }

    #[test]
    fn current_project_json_is_compact_unless_detail_is_explicit() {
        assert!(should_emit_compact(false, true, false, false));
        assert!(!should_emit_compact(false, true, true, false));
        assert!(!should_emit_compact(false, true, false, true));
        assert!(should_emit_compact(true, true, false, false));
        assert!(!should_emit_compact(false, false, false, false));
    }
}
