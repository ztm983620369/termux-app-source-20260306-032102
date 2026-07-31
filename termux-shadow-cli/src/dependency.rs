use std::collections::BTreeSet;
use std::fs::{self, OpenOptions};
use std::io::{Read, Write};
use std::path::{Component, Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use walkdir::{DirEntry, WalkDir};

use crate::build;
use crate::cli::{
    DependencyPolicy, DepsArgs, DepsCleanArgs, DepsCommand, DepsImportArgs, DepsResolveArgs,
    DepsVendorArgs,
};
use crate::context::{AppContext, BuildEnvironment, same_logical_path};
use crate::fsutil::{remove_dir_if_exists, sha256_file, write_atomic};

pub const LOCK_MANIFEST: &str = "shadow-dependencies.lock.json";
const LOCK_SCHEMA_VERSION: u32 = 1;
const VENDOR_SCHEMA_VERSION: u32 = 1;
const CACHE_SEED_MARKER: &str = ".shadow-cache-seed.json";
const MAX_LOCK_FILES: usize = 256;
const MAX_RESOLVED_ARTIFACTS: usize = 4096;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct DependencyLock {
    schema_version: u32,
    declaration_sha256: String,
    generated_at_epoch_ms: u64,
    resolution_policy: String,
    gradle_locks: Vec<LockedFile>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    resolved_artifacts: Vec<ResolvedArtifact>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LockedFile {
    path: String,
    sha256: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
struct ResolvedArtifact {
    component: String,
    file_name: String,
    sha256: String,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct VendorManifest {
    schema_version: u32,
    dependency_lock_sha256: String,
    source_gradle_home: String,
    files: u64,
    bytes: u64,
    content_sha256: String,
    created_at_epoch_ms: u64,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct DependencyResult {
    ok: bool,
    action: &'static str,
    status: &'static str,
    project: Option<String>,
    dependency_policy: &'static str,
    network_allowed: bool,
    lock_status: String,
    lock_manifest: Option<String>,
    shared_cache: String,
    vendor_cache: Option<String>,
    files: u64,
    bytes: u64,
    stats_complete: bool,
    state_changed: bool,
}

#[derive(Debug, Clone)]
pub struct LockValidation {
    pub valid: bool,
    pub status: String,
    pub manifest_sha256: Option<String>,
}

pub fn run(context: &AppContext, args: DepsArgs) -> Result<()> {
    if args.workspace {
        bail!("--workspace is supported only by `shadow-plugin deps audit --workspace`");
    }
    match args.command {
        DepsCommand::Resolve(args) => run_resolve(context, args),
        DepsCommand::Vendor(args) => run_vendor(context, args),
        DepsCommand::ImportGradleCache(args) => run_import(context, args),
        DepsCommand::Status => run_status(context),
        DepsCommand::Audit => run_audit(context),
        DepsCommand::Clean(args) => run_clean(context, args),
    }
}

fn run_audit(context: &AppContext) -> Result<()> {
    let project = context.project()?;
    let validation = validate_lock(&project)?;
    if !validation.valid {
        bail!(
            "DEPENDENCY_AUDIT_FAILED: {}; run `shadow-plugin deps resolve --allow-network --lock` and review the lock",
            validation.status
        );
    }
    let lock_path = project.join(LOCK_MANIFEST);
    let lock: DependencyLock = serde_json::from_slice(&fs::read(&lock_path)?)
        .with_context(|| format!("parse {}", lock_path.display()))?;
    let selected_cache = context
        .build_environment_for_project(&project)
        .map(|environment| environment.gradle_home)
        .unwrap_or_else(|_| context.shadow_home.join("gradle-cache"));
    let mut missing = lock
        .resolved_artifacts
        .iter()
        .map(|artifact| (artifact.file_name.clone(), artifact.sha256.clone()))
        .collect::<BTreeSet<_>>();
    if !missing.is_empty() {
        for entry in WalkDir::new(&selected_cache)
            .follow_links(false)
            .into_iter()
            .filter_map(Result::ok)
            .filter(|entry| entry.file_type().is_file())
        {
            let Some(file_name) = entry.file_name().to_str() else {
                continue;
            };
            if !missing.iter().any(|(expected, _)| expected == file_name) {
                continue;
            }
            let digest = sha256_file(entry.path())?;
            missing.remove(&(file_name.to_owned(), digest));
            if missing.is_empty() {
                break;
            }
        }
    }
    if !missing.is_empty() {
        let examples = missing
            .iter()
            .take(5)
            .map(|(name, sha256)| format!("{name}@{}", &sha256[..12]))
            .collect::<Vec<_>>()
            .join(", ");
        bail!(
            "DEPENDENCY_AUDIT_FAILED: {} locked artifact(s) are absent from {}; missing: {}; import/warm the cache or create a vendor snapshot",
            missing.len(),
            selected_cache.display(),
            examples
        );
    }
    emit(
        context,
        DependencyResult {
            ok: true,
            action: "deps.audit",
            status: "PASS",
            project: Some(project.display().to_string()),
            dependency_policy: context.dependency_policy.as_str(),
            network_allowed: false,
            lock_status: validation.status,
            lock_manifest: validation.manifest_sha256,
            shared_cache: selected_cache.display().to_string(),
            vendor_cache: vendor_is_usable(&project)
                .then(|| project.join("vendor/gradle-home").display().to_string()),
            files: lock.resolved_artifacts.len().try_into().unwrap_or(u64::MAX),
            bytes: 0,
            stats_complete: true,
            state_changed: false,
        },
    )
}

pub fn ensure_lock(context: &AppContext, project: &Path) -> Result<LockValidation> {
    let validation = validate_lock(project)?;
    if validation.valid {
        return Ok(validation);
    }
    let allow_network =
        context.dependency_policy == DependencyPolicy::Online || context.allow_network;
    let resolution_context = context.with_dependency_policy(
        if allow_network {
            DependencyPolicy::Online
        } else {
            DependencyPolicy::Offline
        },
        allow_network,
    );
    resolve_and_lock(&resolution_context, project, allow_network, false)?;
    let validation = validate_lock(project)?;
    if !validation.valid {
        bail!(
            "DEPENDENCY_LOCK_INVALID: dependency resolution completed but the lock is invalid: {}; rerun `shadow-plugin deps resolve --allow-network --lock`",
            validation.status
        );
    }
    Ok(validation)
}

pub fn require_valid_lock(project: &Path) -> Result<LockValidation> {
    let validation = validate_lock(project)?;
    if !validation.valid {
        bail!(
            "DEPENDENCY_LOCK_REQUIRED: {}; run `shadow-plugin deps resolve --allow-network --lock`, review the lock, then rerun the publish command",
            validation.status
        );
    }
    Ok(validation)
}

pub fn verify_locked_artifacts(context: &AppContext, project: &Path) -> Result<()> {
    require_valid_lock(project)?;
    let lock_path = project.join(LOCK_MANIFEST);
    let lock: DependencyLock = serde_json::from_slice(&fs::read(&lock_path)?)
        .with_context(|| format!("parse {}", lock_path.display()))?;
    let offline = context.with_dependency_policy(DependencyPolicy::Offline, false);
    resolve_gradle(&offline, project, false, false)?;
    let actual = read_resolved_artifacts(project)?;
    if actual != lock.resolved_artifacts {
        let expected = lock.resolved_artifacts.len();
        let resolved = actual.len();
        bail!(
            "DEPENDENCY_ARTIFACT_MISMATCH: offline resolution produced {resolved} artifact(s), but the committed lock contains {expected}; rerun `shadow-plugin deps resolve --allow-network --lock`, review the dependency change, and retry"
        );
    }
    Ok(())
}

pub fn validate_lock(project: &Path) -> Result<LockValidation> {
    let path = project.join(LOCK_MANIFEST);
    if !path.is_file() {
        return Ok(LockValidation {
            valid: false,
            status: format!("{LOCK_MANIFEST} is missing"),
            manifest_sha256: None,
        });
    }
    let bytes = fs::read(&path).with_context(|| format!("read {}", path.display()))?;
    let lock: DependencyLock = match serde_json::from_slice(&bytes) {
        Ok(lock) => lock,
        Err(error) => {
            return Ok(LockValidation {
                valid: false,
                status: format!("cannot parse {LOCK_MANIFEST}: {error}"),
                manifest_sha256: Some(sha256_file(&path)?),
            });
        }
    };
    if lock.schema_version != LOCK_SCHEMA_VERSION {
        return Ok(LockValidation {
            valid: false,
            status: format!("unsupported dependency lock schema {}", lock.schema_version),
            manifest_sha256: Some(sha256_file(&path)?),
        });
    }
    let declaration_sha256 = declaration_fingerprint(project)?;
    if declaration_sha256 != lock.declaration_sha256 {
        return Ok(LockValidation {
            valid: false,
            status: "Gradle dependency declarations changed after the lock was generated".into(),
            manifest_sha256: Some(sha256_file(&path)?),
        });
    }
    if lock.gradle_locks.len() > MAX_LOCK_FILES {
        return Ok(LockValidation {
            valid: false,
            status: "dependency lock references too many Gradle lockfiles".into(),
            manifest_sha256: Some(sha256_file(&path)?),
        });
    }
    for entry in &lock.gradle_locks {
        let relative = safe_relative(&entry.path)?;
        let file = project.join(relative);
        if !file.is_file() {
            return Ok(LockValidation {
                valid: false,
                status: format!("locked Gradle file is missing: {}", entry.path),
                manifest_sha256: Some(sha256_file(&path)?),
            });
        }
        if sha256_file(&file)? != entry.sha256 {
            return Ok(LockValidation {
                valid: false,
                status: format!("locked Gradle file changed: {}", entry.path),
                manifest_sha256: Some(sha256_file(&path)?),
            });
        }
    }
    if lock.resolved_artifacts.len() > MAX_RESOLVED_ARTIFACTS {
        return Ok(LockValidation {
            valid: false,
            status: "dependency lock references too many resolved artifacts".into(),
            manifest_sha256: Some(sha256_file(&path)?),
        });
    }
    let mut artifact_identities = BTreeSet::new();
    for artifact in &lock.resolved_artifacts {
        let valid_component = !artifact.component.is_empty() && artifact.component.len() <= 256;
        let valid_file_name = !artifact.file_name.is_empty()
            && artifact.file_name.len() <= 256
            && !artifact.file_name.contains('/')
            && !artifact.file_name.contains('\\');
        let valid_sha256 = artifact.sha256.len() == 64
            && artifact
                .sha256
                .chars()
                .all(|character| character.is_ascii_hexdigit());
        if !valid_component || !valid_file_name || !valid_sha256 {
            return Ok(LockValidation {
                valid: false,
                status: format!(
                    "dependency lock contains an invalid resolved artifact: {}",
                    artifact.file_name
                ),
                manifest_sha256: Some(sha256_file(&path)?),
            });
        }
        if !artifact_identities.insert((
            artifact.component.as_str(),
            artifact.file_name.as_str(),
            artifact.sha256.as_str(),
        )) {
            return Ok(LockValidation {
                valid: false,
                status: format!(
                    "dependency lock contains a duplicate resolved artifact: {}",
                    artifact.file_name
                ),
                manifest_sha256: Some(sha256_file(&path)?),
            });
        }
    }
    Ok(LockValidation {
        valid: true,
        status: "LOCKED".into(),
        manifest_sha256: Some(sha256_file(&path)?),
    })
}

pub fn prepare_cache_layers(environment: &BuildEnvironment) -> Result<()> {
    create_private_dir(&environment.gradle_home)?;
    let Some(base) = environment.base_gradle_home.as_deref() else {
        return Ok(());
    };
    if same_logical_path(base, &environment.gradle_home) {
        return Ok(());
    }
    let source_modules = base.join("caches/modules-2");
    let source_wrapper = base.join("wrapper/dists");
    if !source_modules.is_dir() && !source_wrapper.is_dir() {
        return Ok(());
    }
    let source_identity = cache_seed_identity(base)?;
    let marker_path = environment.gradle_home.join(CACHE_SEED_MARKER);
    let marker_matches = fs::read(&marker_path)
        .ok()
        .and_then(|bytes| serde_json::from_slice::<serde_json::Value>(&bytes).ok())
        .and_then(|value| {
            value
                .get("sourceIdentity")
                .and_then(|value| value.as_str())
                .map(str::to_owned)
        })
        .as_deref()
        == Some(source_identity.as_str());
    let module_ready =
        !source_modules.is_dir() || environment.gradle_home.join("caches/modules-2").is_dir();
    let wrapper_ready =
        !source_wrapper.is_dir() || environment.gradle_home.join("wrapper/dists").is_dir();
    if marker_matches && module_ready && wrapper_ready {
        return Ok(());
    }
    copy_selected_cache(
        &base.join("caches"),
        &environment.gradle_home.join("caches"),
    )?;
    merge_tree(
        &source_wrapper,
        &environment.gradle_home.join("wrapper/dists"),
        false,
    )?;
    let marker = serde_json::json!({
        "schemaVersion": 1,
        "source": base.display().to_string(),
        "sourceIdentity": source_identity,
        "seededAtEpochMs": now_millis(),
    });
    write_json(&marker_path, &marker)
}

fn cache_seed_identity(base: &Path) -> Result<String> {
    let mut digest = Sha256::new();
    digest.update(base.to_string_lossy().as_bytes());
    for relative in ["caches/modules-2", "wrapper/dists"] {
        let path = base.join(relative);
        digest.update(relative.as_bytes());
        if let Ok(metadata) = fs::metadata(&path) {
            digest.update(metadata.len().to_le_bytes());
            let modified = metadata
                .modified()
                .ok()
                .and_then(|value| value.duration_since(UNIX_EPOCH).ok())
                .map(|value| value.as_nanos())
                .unwrap_or_default();
            digest.update(modified.to_le_bytes());
        }
    }
    Ok(format!("{:x}", digest.finalize()))
}

pub fn vendor_is_usable(project: &Path) -> bool {
    let Ok(lock) = validate_lock(project) else {
        return false;
    };
    if !lock.valid || !project.join("vendor/gradle-home/caches/modules-2").is_dir() {
        return false;
    }
    let Ok(bytes) = fs::read(project.join("vendor/manifest.json")) else {
        return false;
    };
    let Ok(manifest) = serde_json::from_slice::<VendorManifest>(&bytes) else {
        return false;
    };
    manifest.schema_version == VENDOR_SCHEMA_VERSION
        && lock.manifest_sha256.as_deref() == Some(manifest.dependency_lock_sha256.as_str())
}

fn run_resolve(context: &AppContext, args: DepsResolveArgs) -> Result<()> {
    let project = context.project()?;
    let allow_network = args.allow_network
        || context.allow_network
        || context.dependency_policy == DependencyPolicy::Online;
    if context.dependency_policy == DependencyPolicy::Offline && allow_network {
        bail!("--offline cannot be combined with dependency network resolution");
    }
    if !args.lock && !context.json {
        println!("Dependency resolution will warm the shared cache without committing a lock.");
    }
    let policy = if allow_network {
        DependencyPolicy::Online
    } else {
        DependencyPolicy::Offline
    };
    let resolution_context = context.with_dependency_policy(policy, allow_network);
    let selected_cache = resolution_context
        .build_environment_for_project(&project)?
        .gradle_home;
    if args.lock {
        resolve_and_lock(&resolution_context, &project, allow_network, args.refresh)?;
    } else {
        resolve_gradle(&resolution_context, &project, false, args.refresh)?;
    }
    let validation = validate_lock(&project)?;
    emit(
        context,
        DependencyResult {
            ok: true,
            action: "deps.resolve",
            status: if args.lock { "LOCKED" } else { "RESOLVED" },
            project: Some(project.display().to_string()),
            dependency_policy: policy.as_str(),
            network_allowed: allow_network,
            lock_status: validation.status,
            lock_manifest: validation.manifest_sha256,
            shared_cache: selected_cache.display().to_string(),
            vendor_cache: vendor_is_usable(&project)
                .then(|| project.join("vendor/gradle-home").display().to_string()),
            files: 0,
            bytes: 0,
            stats_complete: true,
            state_changed: true,
        },
    )
}

fn resolve_and_lock(
    context: &AppContext,
    project: &Path,
    allow_network: bool,
    refresh: bool,
) -> Result<()> {
    resolve_gradle(context, project, true, refresh)?;
    let resolved_artifacts = read_resolved_artifacts(project)?;
    if resolved_artifacts.len() > MAX_RESOLVED_ARTIFACTS {
        bail!("dependency resolution returned too many artifacts");
    }
    let gradle_locks = gradle_lock_files(project)?
        .into_iter()
        .map(|path| {
            Ok(LockedFile {
                path: path.strip_prefix(project)?.to_string_lossy().into_owned(),
                sha256: sha256_file(&path)?,
            })
        })
        .collect::<Result<Vec<_>>>()?;
    let lock = DependencyLock {
        schema_version: LOCK_SCHEMA_VERSION,
        declaration_sha256: declaration_fingerprint(project)?,
        generated_at_epoch_ms: now_millis(),
        resolution_policy: if allow_network { "online" } else { "offline" }.into(),
        gradle_locks,
        resolved_artifacts,
    };
    write_json(&project.join(LOCK_MANIFEST), &lock)
}

fn resolve_gradle(
    context: &AppContext,
    project: &Path,
    write_locks: bool,
    refresh: bool,
) -> Result<()> {
    let environment = context.build_environment_for_project(project)?;
    prepare_cache_layers(&environment)?;
    build::write_local_properties(project, &environment)?;
    let mut arguments = Vec::new();
    if write_locks {
        arguments.push("--write-locks".to_owned());
    }
    if refresh {
        arguments.push("--refresh-dependencies".to_owned());
    }
    arguments.push("resolveShadowPluginDependencies".to_owned());
    build::run_gradle(context, project, &environment, &arguments).map(|_| ())
}

fn run_vendor(context: &AppContext, args: DepsVendorArgs) -> Result<()> {
    let project = context.project()?;
    let lock = require_valid_lock(&project)?;
    let lock_sha256 = lock
        .manifest_sha256
        .context("dependency lock SHA is missing")?;
    let source_environment = context
        .with_dependency_policy(DependencyPolicy::Online, true)
        .build_environment()?;
    prepare_cache_layers(&source_environment)?;
    let source = source_environment.gradle_home.join("caches");
    if !source.join("modules-2").is_dir() {
        bail!(
            "DEPENDENCY_CACHE_EMPTY: shared cache has no Gradle modules; run `shadow-plugin deps resolve --allow-network --lock` first"
        );
    }
    let vendor = project.join("vendor");
    if !args.refresh && vendor_is_usable(&project) {
        return emit(
            context,
            DependencyResult {
                ok: true,
                action: "deps.vendor",
                status: "CURRENT",
                project: Some(project.display().to_string()),
                dependency_policy: "offline",
                network_allowed: false,
                lock_status: "LOCKED".into(),
                lock_manifest: Some(lock_sha256),
                shared_cache: source_environment.gradle_home.display().to_string(),
                vendor_cache: Some(vendor.join("gradle-home").display().to_string()),
                files: 0,
                bytes: 0,
                stats_complete: true,
                state_changed: false,
            },
        );
    }
    let parent = vendor.parent().context("vendor path has no parent")?;
    let staging = parent.join(format!(".vendor.new.{}", std::process::id()));
    remove_dir_if_exists(&staging)?;
    create_private_dir(&staging)?;
    let cache_target = staging.join("gradle-home/caches");
    copy_selected_cache(&source, &cache_target)?;
    let (files, bytes, content_sha256) = tree_stats(&staging.join("gradle-home"))?;
    let manifest = VendorManifest {
        schema_version: VENDOR_SCHEMA_VERSION,
        dependency_lock_sha256: lock_sha256.clone(),
        source_gradle_home: source_environment.gradle_home.display().to_string(),
        files,
        bytes,
        content_sha256,
        created_at_epoch_ms: now_millis(),
    };
    write_json(&staging.join("manifest.json"), &manifest)?;
    let old = parent.join(format!(".vendor.old.{}", std::process::id()));
    remove_dir_if_exists(&old)?;
    if vendor.exists() {
        fs::rename(&vendor, &old)?;
    }
    if let Err(error) = fs::rename(&staging, &vendor) {
        if old.exists() {
            let _ = fs::rename(&old, &vendor);
        }
        return Err(error).context("atomically install vendor cache");
    }
    remove_dir_if_exists(&old)?;
    emit(
        context,
        DependencyResult {
            ok: true,
            action: "deps.vendor",
            status: "VENDORED",
            project: Some(project.display().to_string()),
            dependency_policy: "offline",
            network_allowed: false,
            lock_status: "LOCKED".into(),
            lock_manifest: Some(lock_sha256),
            shared_cache: source_environment.gradle_home.display().to_string(),
            vendor_cache: Some(vendor.join("gradle-home").display().to_string()),
            files,
            bytes,
            stats_complete: true,
            state_changed: true,
        },
    )
}

fn run_import(context: &AppContext, args: DepsImportArgs) -> Result<()> {
    let source = args
        .from
        .unwrap_or_else(|| context.termux_home.join(".gradle"));
    let target = context.shadow_home.join("gradle-cache");
    if !source.is_dir() {
        bail!("Gradle cache source does not exist: {}", source.display());
    }
    if same_logical_path(&source, &target) {
        bail!("source and managed Gradle cache are the same directory");
    }
    let (files, bytes, _) = tree_stats(&source.join("caches"))?;
    if !args.dry_run {
        create_private_dir(&target)?;
        copy_selected_cache(&source.join("caches"), &target.join("caches"))?;
    }
    emit(
        context,
        DependencyResult {
            ok: true,
            action: "deps.import-gradle-cache",
            status: if args.dry_run { "DRY_RUN" } else { "IMPORTED" },
            project: context
                .project_if_present()
                .map(|path| path.display().to_string()),
            dependency_policy: context.dependency_policy.as_str(),
            network_allowed: context.allow_network,
            lock_status: "UNCHANGED".into(),
            lock_manifest: None,
            shared_cache: target.display().to_string(),
            vendor_cache: None,
            files,
            bytes,
            stats_complete: true,
            state_changed: !args.dry_run,
        },
    )
}

fn run_status(context: &AppContext) -> Result<()> {
    let project = context.project_if_present();
    let validation = project
        .as_deref()
        .map(validate_lock)
        .transpose()?
        .unwrap_or(LockValidation {
            valid: false,
            status: "NO_PROJECT".into(),
            manifest_sha256: None,
        });
    let shared = project
        .as_deref()
        .and_then(|project| context.build_environment_for_project(project).ok())
        .or_else(|| context.build_environment().ok())
        .map(|environment| environment.gradle_home)
        .unwrap_or_else(|| context.shadow_home.join("gradle-cache"));
    let (files, bytes, stats_complete) = quick_tree_stats(&shared, 4_096)?;
    emit(
        context,
        DependencyResult {
            ok: true,
            action: "deps.status",
            status: "OK",
            project: project.as_ref().map(|path| path.display().to_string()),
            dependency_policy: context.dependency_policy.as_str(),
            network_allowed: context.allow_network,
            lock_status: validation.status,
            lock_manifest: validation.manifest_sha256,
            shared_cache: shared.display().to_string(),
            vendor_cache: project.as_ref().and_then(|project| {
                vendor_is_usable(project)
                    .then(|| project.join("vendor/gradle-home").display().to_string())
            }),
            files,
            bytes,
            stats_complete,
            state_changed: false,
        },
    )
}

fn run_clean(context: &AppContext, args: DepsCleanArgs) -> Result<()> {
    let clean_vendor = args.vendor || !args.shared;
    let mut changed = false;
    let project = if clean_vendor {
        Some(context.project()?)
    } else {
        context.project_if_present()
    };
    if clean_vendor {
        let vendor = project.as_ref().unwrap().join("vendor");
        changed |= vendor.exists();
        remove_dir_if_exists(&vendor)?;
    }
    if args.shared {
        if !args.yes {
            bail!("shared dependency cache deletion requires --shared --yes");
        }
        let shared = context.shadow_home.join("gradle-cache");
        changed |= shared.exists();
        remove_dir_if_exists(&shared)?;
    }
    emit(
        context,
        DependencyResult {
            ok: true,
            action: "deps.clean",
            status: "CLEANED",
            project: project.as_ref().map(|path| path.display().to_string()),
            dependency_policy: context.dependency_policy.as_str(),
            network_allowed: false,
            lock_status: "PRESERVED".into(),
            lock_manifest: project
                .as_ref()
                .and_then(|project| validate_lock(project).ok())
                .and_then(|validation| validation.manifest_sha256),
            shared_cache: context
                .shadow_home
                .join("gradle-cache")
                .display()
                .to_string(),
            vendor_cache: None,
            files: 0,
            bytes: 0,
            stats_complete: true,
            state_changed: changed,
        },
    )
}

fn declaration_fingerprint(project: &Path) -> Result<String> {
    let mut files = WalkDir::new(project)
        .follow_links(false)
        .into_iter()
        .filter_entry(|entry| include_declaration_entry(project, entry))
        .filter_map(Result::ok)
        .filter(|entry| entry.file_type().is_file())
        .filter_map(|entry| {
            let relative = entry.path().strip_prefix(project).ok()?;
            dependency_declaration(relative).then(|| entry.path().to_path_buf())
        })
        .collect::<Vec<_>>();
    files.sort();
    let mut digest = Sha256::new();
    digest.update(b"termux-shadow-dependency-declarations-v1\0");
    for file in files {
        let relative = file.strip_prefix(project)?;
        digest.update(relative.to_string_lossy().as_bytes());
        digest.update([0]);
        let mut input = fs::File::open(&file)?;
        let mut buffer = [0u8; 64 * 1024];
        loop {
            let read = input.read(&mut buffer)?;
            if read == 0 {
                break;
            }
            digest.update(&buffer[..read]);
        }
        digest.update([0]);
    }
    Ok(format!("{:x}", digest.finalize()))
}

fn dependency_declaration(relative: &Path) -> bool {
    let name = relative.file_name().and_then(|name| name.to_str());
    relative
        .components()
        .any(|component| matches!(component, Component::Normal(value) if value == "libs"))
        || matches!(
            name,
            Some(
                "build.gradle"
                    | "build.gradle.kts"
                    | "dependencies.gradle"
                    | "settings.gradle"
                    | "settings.gradle.kts"
                    | "gradle.properties"
                    | "libs.versions.toml"
            )
        )
}

fn include_declaration_entry(project: &Path, entry: &DirEntry) -> bool {
    let Ok(relative) = entry.path().strip_prefix(project) else {
        return false;
    };
    !relative.components().any(|component| {
        matches!(
            component,
            Component::Normal(value)
                if matches!(
                    value.to_str(),
                    Some(".git" | ".gradle" | "build" | "dist" | "vendor" | "out")
                )
        )
    })
}

fn gradle_lock_files(project: &Path) -> Result<Vec<PathBuf>> {
    let mut files = WalkDir::new(project)
        .follow_links(false)
        .into_iter()
        .filter_entry(|entry| include_declaration_entry(project, entry))
        .filter_map(Result::ok)
        .filter(|entry| entry.file_type().is_file())
        .filter(|entry| {
            entry.file_name() == "gradle.lockfile"
                || entry
                    .path()
                    .components()
                    .any(|component| component.as_os_str() == "dependency-locks")
        })
        .map(|entry| entry.path().to_path_buf())
        .collect::<Vec<_>>();
    files.sort();
    if files.len() > MAX_LOCK_FILES {
        bail!("too many Gradle dependency lockfiles");
    }
    Ok(files)
}

fn read_resolved_artifacts(project: &Path) -> Result<Vec<ResolvedArtifact>> {
    let path = project.join("build/reports/shadow-dependencies.json");
    if !path.is_file() {
        bail!(
            "DEPENDENCY_REPORT_MISSING: Gradle did not create {}; sync the canonical tooling and rerun dependency resolution",
            path.display()
        );
    }
    if fs::metadata(&path)?.len() > 8 * 1024 * 1024 {
        bail!("dependency resolution report is unexpectedly large");
    }
    let value: serde_json::Value = serde_json::from_slice(&fs::read(&path)?)?;
    if value.get("schemaVersion").and_then(|value| value.as_u64()) != Some(1) {
        bail!("dependency resolution report has an unsupported schema");
    }
    let items = value
        .get("artifacts")
        .and_then(|value| value.as_array())
        .context("dependency resolution report is missing artifacts")?;
    if items.len() > MAX_RESOLVED_ARTIFACTS {
        bail!("dependency resolution returned too many artifacts");
    }
    let mut artifacts = Vec::new();
    for item in items {
        let component = item
            .get("component")
            .and_then(|value| value.as_str())
            .filter(|value| !value.is_empty())
            .context("dependency artifact has no component")?;
        let file_name = item
            .get("fileName")
            .and_then(|value| value.as_str())
            .filter(|value| !value.is_empty())
            .context("dependency artifact has no fileName")?;
        if component.chars().count() > 256 || file_name.chars().count() > 256 {
            bail!("dependency artifact identity exceeds 256 characters");
        }
        let sha256 = item
            .get("sha256")
            .and_then(|value| value.as_str())
            .context("dependency artifact has no sha256")?;
        if sha256.len() != 64
            || !sha256
                .chars()
                .all(|character| character.is_ascii_hexdigit())
        {
            bail!("dependency artifact has an invalid SHA-256");
        }
        artifacts.push(ResolvedArtifact {
            component: component.to_owned(),
            file_name: file_name.to_owned(),
            sha256: sha256.to_ascii_lowercase(),
        });
    }
    artifacts.sort_by(|left, right| {
        left.component
            .cmp(&right.component)
            .then(left.file_name.cmp(&right.file_name))
    });
    artifacts.dedup_by(|left, right| {
        left.component == right.component
            && left.file_name == right.file_name
            && left.sha256 == right.sha256
    });
    Ok(artifacts)
}

fn copy_selected_cache(source: &Path, target: &Path) -> Result<()> {
    if !source.is_dir() {
        return Ok(());
    }
    for entry in fs::read_dir(source)? {
        let entry = entry?;
        let name = entry.file_name();
        let text = name.to_string_lossy();
        if text == "modules-2" || text.starts_with("transforms-") || text.starts_with("jars-") {
            merge_tree(&entry.path(), &target.join(name), true)?;
        }
    }
    Ok(())
}

fn merge_tree(source: &Path, target: &Path, overwrite: bool) -> Result<()> {
    if !source.is_dir() {
        return Ok(());
    }
    for entry in WalkDir::new(source).follow_links(false) {
        let entry = entry?;
        let relative = entry.path().strip_prefix(source)?;
        if relative.as_os_str().is_empty() || transient_cache_path(relative) {
            continue;
        }
        let destination = target.join(relative);
        if entry.file_type().is_dir() {
            create_private_dir(&destination)?;
        } else if entry.file_type().is_file() {
            if !overwrite && destination.is_file() {
                continue;
            }
            copy_file_atomic(entry.path(), &destination)?;
        }
    }
    Ok(())
}

fn transient_cache_path(relative: &Path) -> bool {
    relative.components().any(|component| {
        let text = component.as_os_str().to_string_lossy();
        text.ends_with(".lock")
            || text.ends_with(".tmp")
            || text == "gc.properties"
            || text == "journal-1"
    })
}

fn copy_file_atomic(source: &Path, target: &Path) -> Result<()> {
    let parent = target.parent().context("cache target has no parent")?;
    create_private_dir(parent)?;
    let temporary = parent.join(format!(
        ".{}.{}.part",
        target.file_name().unwrap_or_default().to_string_lossy(),
        std::process::id()
    ));
    let mut input = fs::File::open(source)?;
    let mut output = OpenOptions::new()
        .create(true)
        .truncate(true)
        .write(true)
        .open(&temporary)?;
    std::io::copy(&mut input, &mut output)?;
    output.flush()?;
    output.sync_all()?;
    drop(output);
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&temporary, fs::Permissions::from_mode(0o600))?;
    }
    fs::rename(&temporary, target)?;
    Ok(())
}

fn tree_stats(path: &Path) -> Result<(u64, u64, String)> {
    if !path.is_dir() {
        return Ok((0, 0, format!("{:x}", Sha256::digest([]))));
    }
    let mut entries = WalkDir::new(path)
        .follow_links(false)
        .into_iter()
        .filter_map(Result::ok)
        .filter(|entry| entry.file_type().is_file())
        .filter_map(|entry| {
            let relative = entry.path().strip_prefix(path).ok()?.to_path_buf();
            (!transient_cache_path(&relative)).then(|| (relative, entry.path().to_path_buf()))
        })
        .collect::<Vec<_>>();
    entries.sort_by(|left, right| left.0.cmp(&right.0));
    let mut digest = Sha256::new();
    let mut files = 0u64;
    let mut bytes = 0u64;
    for (relative, absolute) in entries {
        let metadata = fs::metadata(&absolute)?;
        files = files.saturating_add(1);
        bytes = bytes.saturating_add(metadata.len());
        digest.update(relative.to_string_lossy().as_bytes());
        digest.update([0]);
        digest.update(sha256_file(&absolute)?.as_bytes());
        digest.update([0]);
    }
    Ok((files, bytes, format!("{:x}", digest.finalize())))
}

fn quick_tree_stats(path: &Path, limit: u64) -> Result<(u64, u64, bool)> {
    if !path.is_dir() {
        return Ok((0, 0, true));
    }
    let mut files = 0u64;
    let mut bytes = 0u64;
    let mut complete = true;
    for entry in WalkDir::new(path).follow_links(false).into_iter() {
        let entry = entry?;
        if !entry.file_type().is_file() || transient_cache_path(entry.path()) {
            continue;
        }
        if files >= limit {
            complete = false;
            break;
        }
        files = files.saturating_add(1);
        bytes = bytes.saturating_add(entry.metadata()?.len());
    }
    Ok((files, bytes, complete))
}

fn safe_relative(value: &str) -> Result<&Path> {
    let path = Path::new(value);
    if path.is_absolute()
        || path.components().any(|component| {
            matches!(
                component,
                Component::ParentDir | Component::RootDir | Component::Prefix(_)
            )
        })
    {
        bail!("dependency lock contains an unsafe path: {value}");
    }
    Ok(path)
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

fn write_json(path: &Path, value: &impl Serialize) -> Result<()> {
    let mut bytes = serde_json::to_vec_pretty(value)?;
    bytes.push(b'\n');
    write_atomic(path, &bytes)
}

fn emit(context: &AppContext, output: DependencyResult) -> Result<()> {
    if context.json {
        println!("{}", serde_json::to_string_pretty(&output)?);
    } else {
        println!("{}: {}", output.action, output.status);
        if let Some(project) = &output.project {
            println!("  project: {project}");
        }
        println!("  policy: {}", output.dependency_policy);
        println!(
            "  network: {}",
            if output.network_allowed {
                "allowed"
            } else {
                "disabled"
            }
        );
        println!("  lock: {}", output.lock_status);
        println!("  shared cache: {}", output.shared_cache);
        if let Some(vendor) = &output.vendor_cache {
            println!("  vendor cache: {vendor}");
        }
        if output.files > 0 || output.bytes > 0 {
            println!(
                "  cache content: {}{} files, {} bytes",
                if output.stats_complete { "" } else { ">=" },
                output.files,
                output.bytes
            );
        }
    }
    Ok(())
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
    use super::{
        DependencyLock, LOCK_MANIFEST, LOCK_SCHEMA_VERSION, LockedFile, ResolvedArtifact,
        declaration_fingerprint, read_resolved_artifacts, validate_lock,
    };
    use std::fs;

    #[test]
    fn dependency_lock_detects_build_file_changes() {
        let root = tempfile::tempdir().unwrap();
        fs::write(root.path().join("build.gradle"), "dependencies {}\n").unwrap();
        fs::write(
            root.path().join("settings.gradle"),
            "rootProject.name='x'\n",
        )
        .unwrap();
        let lock = DependencyLock {
            schema_version: LOCK_SCHEMA_VERSION,
            declaration_sha256: declaration_fingerprint(root.path()).unwrap(),
            generated_at_epoch_ms: 1,
            resolution_policy: "offline".into(),
            gradle_locks: Vec::new(),
            resolved_artifacts: Vec::new(),
        };
        fs::write(
            root.path().join(LOCK_MANIFEST),
            serde_json::to_vec_pretty(&lock).unwrap(),
        )
        .unwrap();
        assert!(validate_lock(root.path()).unwrap().valid);
        fs::write(
            root.path().join("build.gradle"),
            "dependencies { implementation 'x:y:1' }\n",
        )
        .unwrap();
        assert!(!validate_lock(root.path()).unwrap().valid);
    }

    #[test]
    fn dependency_lock_detects_project_owned_dependency_changes() {
        let root = tempfile::tempdir().unwrap();
        fs::create_dir_all(root.path().join("plugin-app")).unwrap();
        fs::write(root.path().join("build.gradle"), "plugins {}\n").unwrap();
        fs::write(
            root.path().join("plugin-app/dependencies.gradle"),
            "dependencies {}\n",
        )
        .unwrap();
        let before = declaration_fingerprint(root.path()).unwrap();
        fs::write(
            root.path().join("plugin-app/dependencies.gradle"),
            "dependencies { implementation 'x:y:1' }\n",
        )
        .unwrap();
        assert_ne!(before, declaration_fingerprint(root.path()).unwrap());
    }

    #[test]
    fn dependency_lock_rejects_changed_gradle_lockfile() {
        let root = tempfile::tempdir().unwrap();
        fs::write(root.path().join("build.gradle"), "dependencies {}\n").unwrap();
        fs::write(root.path().join("gradle.lockfile"), "a:b:1=runtime\n").unwrap();
        let lock = DependencyLock {
            schema_version: LOCK_SCHEMA_VERSION,
            declaration_sha256: declaration_fingerprint(root.path()).unwrap(),
            generated_at_epoch_ms: 1,
            resolution_policy: "offline".into(),
            gradle_locks: vec![LockedFile {
                path: "gradle.lockfile".into(),
                sha256: crate::fsutil::sha256_file(&root.path().join("gradle.lockfile")).unwrap(),
            }],
            resolved_artifacts: Vec::new(),
        };
        fs::write(
            root.path().join(LOCK_MANIFEST),
            serde_json::to_vec_pretty(&lock).unwrap(),
        )
        .unwrap();
        assert!(validate_lock(root.path()).unwrap().valid);
        fs::write(root.path().join("gradle.lockfile"), "a:b:2=runtime\n").unwrap();
        assert!(!validate_lock(root.path()).unwrap().valid);
    }

    #[test]
    fn dependency_lock_rejects_unsafe_resolved_artifact_identity() {
        let root = tempfile::tempdir().unwrap();
        fs::write(root.path().join("build.gradle"), "dependencies {}\n").unwrap();
        let lock = DependencyLock {
            schema_version: LOCK_SCHEMA_VERSION,
            declaration_sha256: declaration_fingerprint(root.path()).unwrap(),
            generated_at_epoch_ms: 1,
            resolution_policy: "offline".into(),
            gradle_locks: Vec::new(),
            resolved_artifacts: vec![ResolvedArtifact {
                component: "g:a:1".into(),
                file_name: "../a-1.jar".into(),
                sha256: "a".repeat(64),
            }],
        };
        fs::write(
            root.path().join(LOCK_MANIFEST),
            serde_json::to_vec_pretty(&lock).unwrap(),
        )
        .unwrap();
        let validation = validate_lock(root.path()).unwrap();
        assert!(!validation.valid);
        assert!(validation.status.contains("invalid resolved artifact"));
    }

    #[test]
    fn resolved_artifact_report_is_closed_and_normalizes_sha256() {
        let root = tempfile::tempdir().unwrap();
        let report = root.path().join("build/reports/shadow-dependencies.json");
        fs::create_dir_all(report.parent().unwrap()).unwrap();
        fs::write(
            &report,
            r#"{"schemaVersion":1,"artifacts":[{"component":"g:a:1","fileName":"a-1.jar","sha256":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}]}"#,
        )
        .unwrap();
        assert_eq!(
            read_resolved_artifacts(root.path()).unwrap(),
            vec![ResolvedArtifact {
                component: "g:a:1".into(),
                file_name: "a-1.jar".into(),
                sha256: "a".repeat(64),
            }]
        );
        fs::write(
            &report,
            r#"{"schemaVersion":1,"artifacts":[{"component":"g:a:1","fileName":"a-1.jar","sha256":"not-a-digest"}]}"#,
        )
        .unwrap();
        assert!(read_resolved_artifacts(root.path()).is_err());
    }
}
