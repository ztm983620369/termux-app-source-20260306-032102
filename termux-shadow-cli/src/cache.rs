use std::env;
use std::fs::{self, File};
use std::io::Read;
use std::path::{Component, Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use walkdir::{DirEntry, WalkDir};

use crate::context::{BuildEnvironment, normalize_termux_path_identity};
use crate::fsutil::{set_private_permissions, sha256_file, write_atomic};

const CACHE_SCHEMA_VERSION: u32 = 1;
const MAX_CACHE_ENTRIES: usize = 16;
const CACHE_PATH: &str = ".gradle/shadow-native-cache.json";

#[derive(Debug, Clone)]
pub struct CacheHit {
    pub artifact: PathBuf,
    pub sha256: String,
}

#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CacheFile {
    schema_version: u32,
    entries: Vec<CacheEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CacheEntry {
    input_fingerprint: String,
    version_code: u64,
    version_name: String,
    artifact_path: String,
    artifact_scope: String,
    artifact_sha256: String,
    validated_at: u64,
}

pub fn input_fingerprint(
    project: &Path,
    environment: &BuildEnvironment,
    version_code: u64,
    version_name: &str,
) -> Result<String> {
    let mut digest = Sha256::new();
    digest.update(b"termux-shadow-native-cache-v2\0");
    digest.update(source_fingerprint(project, environment)?.as_bytes());
    digest.update([0]);
    digest.update(version_code.to_le_bytes());
    digest.update(version_name.as_bytes());
    digest.update([0]);
    Ok(format!("{:x}", digest.finalize()))
}

pub fn source_fingerprint(project: &Path, environment: &BuildEnvironment) -> Result<String> {
    let mut digest = Sha256::new();
    digest.update(b"termux-shadow-source-v2\0");
    digest.update(env!("CARGO_PKG_VERSION").as_bytes());
    digest.update([0]);
    hash_project_inputs(project, &mut digest)?;
    digest.update(toolchain_fingerprint(environment)?.as_bytes());
    digest.update([0]);

    if let Some(key_id) = env::var_os("TERMUX_SHADOW_SIGNING_KEY_ID") {
        digest.update(b"signing-key-id\0");
        digest.update(key_id.to_string_lossy().as_bytes());
        digest.update([0]);
    }
    if let Some(key_path) = env::var_os("TERMUX_SHADOW_SIGNING_KEY_PKCS8") {
        digest.update(b"signing-key-content\0");
        hash_file_contents(Path::new(&key_path), &mut digest)?;
    }
    Ok(format!("{:x}", digest.finalize()))
}

/// Fingerprints only project-owned build inputs. Unlike `source_fingerprint`, this remains
/// available when an Android toolchain is not installed, so context cursors cannot silently miss
/// local edits.
pub fn project_content_fingerprint(project: &Path) -> Result<String> {
    let mut digest = Sha256::new();
    digest.update(b"termux-shadow-project-content-v1\0");
    hash_project_inputs(project, &mut digest)?;
    Ok(format!("{:x}", digest.finalize()))
}

pub fn toolchain_fingerprint(environment: &BuildEnvironment) -> Result<String> {
    let mut digest = Sha256::new();
    digest.update(b"termux-shadow-toolchain-v1\0");
    for path in toolchain_markers(environment) {
        hash_file_metadata(&path, &mut digest)?;
    }
    Ok(format!("{:x}", digest.finalize()))
}

pub fn lookup(
    project: &Path,
    fingerprint: &str,
    version_code: u64,
    version_name: &str,
    require_dist: bool,
) -> Result<Option<CacheHit>> {
    let cache = read_cache(&project.join(CACHE_PATH));
    let Some(entry) = cache.entries.iter().find(|entry| {
        entry.input_fingerprint == fingerprint
            && entry.version_code == version_code
            && entry.version_name == version_name
            && (!require_dist || entry.artifact_scope == "dist")
    }) else {
        return Ok(None);
    };
    let relative = Path::new(&entry.artifact_path);
    if relative.is_absolute()
        || relative
            .components()
            .any(|component| matches!(component, Component::ParentDir))
    {
        return Ok(None);
    }
    if require_dist && !relative.starts_with("dist") {
        return Ok(None);
    }
    let artifact = project.join(relative);
    if !artifact.is_file() || sha256_file(&artifact)? != entry.artifact_sha256 {
        return Ok(None);
    }
    Ok(Some(CacheHit {
        artifact,
        sha256: entry.artifact_sha256.clone(),
    }))
}

pub fn store(
    project: &Path,
    fingerprint: String,
    version_code: u64,
    version_name: String,
    artifact: &Path,
    sha256: String,
) -> Result<()> {
    let relative = artifact.strip_prefix(project).with_context(|| {
        format!(
            "validated artifact {} is outside project {}",
            artifact.display(),
            project.display()
        )
    })?;
    let cache_path = project.join(CACHE_PATH);
    let mut cache = read_cache(&cache_path);
    cache.schema_version = CACHE_SCHEMA_VERSION;
    let artifact_scope = if relative.starts_with("dist") {
        "dist"
    } else {
        "package"
    };
    cache.entries.retain(|entry| {
        !(entry.input_fingerprint == fingerprint
            && entry.version_code == version_code
            && entry.version_name == version_name)
            || entry.artifact_scope != artifact_scope
    });
    cache.entries.insert(
        0,
        CacheEntry {
            input_fingerprint: fingerprint,
            version_code,
            version_name,
            artifact_path: relative.to_string_lossy().into_owned(),
            artifact_scope: artifact_scope.to_owned(),
            artifact_sha256: sha256,
            validated_at: now_millis(),
        },
    );
    cache.entries.truncate(MAX_CACHE_ENTRIES);
    write_atomic(&cache_path, serde_json::to_vec_pretty(&cache)?.as_slice())
}

fn read_cache(path: &Path) -> CacheFile {
    let Ok(bytes) = fs::read(path) else {
        return CacheFile::default();
    };
    let _ = set_private_permissions(path);
    let Ok(cache) = serde_json::from_slice::<CacheFile>(&bytes) else {
        return CacheFile::default();
    };
    if cache.schema_version == CACHE_SCHEMA_VERSION {
        cache
    } else {
        CacheFile::default()
    }
}

fn hash_project_inputs(project: &Path, digest: &mut Sha256) -> Result<()> {
    if !project.is_dir() {
        bail!("plugin project not found: {}", project.display());
    }
    let mut paths = WalkDir::new(project)
        .follow_links(false)
        .into_iter()
        .filter_entry(|entry| !generated_entry(entry, project))
        .filter_map(Result::ok)
        .filter(|entry| entry.file_type().is_file() || entry.file_type().is_symlink())
        .filter_map(|entry| {
            let relative = entry.path().strip_prefix(project).ok()?;
            is_project_build_input(relative)
                .then(|| (relative.to_path_buf(), entry.path().to_path_buf()))
        })
        .collect::<Vec<_>>();
    paths.sort_by(|left, right| left.0.cmp(&right.0));
    for (relative, absolute) in paths {
        digest.update(relative.to_string_lossy().as_bytes());
        digest.update([0]);
        if absolute.is_symlink() {
            digest.update(b"symlink\0");
            digest.update(fs::read_link(&absolute)?.to_string_lossy().as_bytes());
            digest.update([0]);
        } else {
            hash_file_contents(&absolute, digest)?;
        }
    }
    Ok(())
}

pub(crate) fn is_project_build_input(relative: &Path) -> bool {
    let file_name = relative.file_name().and_then(|name| name.to_str());
    let in_build_tree = relative.components().any(|component| {
        let Component::Normal(component) = component else {
            return false;
        };
        matches!(
            component.to_str(),
            Some("src" | "libs" | "gradle" | "shadow" | "buildSrc" | "build-logic")
        )
    });
    in_build_tree
        || matches!(
            file_name,
            Some(
                "shadow-plugin.properties"
                    | "build.gradle"
                    | "build.gradle.kts"
                    | "settings.gradle"
                    | "settings.gradle.kts"
                    | "gradle.properties"
                    | "gradle.lockfile"
                    | "shadow-dependencies.lock.json"
                    | "gradlew"
                    | "gradlew.bat"
                    | "proguard-rules.pro"
            )
        )
        || file_name.is_some_and(|name| name.ends_with(".gradle") || name.ends_with(".gradle.kts"))
}

pub(crate) fn generated_entry(entry: &DirEntry, root: &Path) -> bool {
    let Ok(relative) = entry.path().strip_prefix(root) else {
        return false;
    };
    relative == Path::new("local.properties")
        || relative.components().any(|component| {
            let Component::Normal(component) = component else {
                return false;
            };
            matches!(
                component.to_str(),
                Some(
                    ".gradle"
                        | "build"
                        | "dist"
                        | ".git"
                        | ".idea"
                        | ".cxx"
                        | ".externalNativeBuild"
                )
            )
        })
}

fn toolchain_markers(environment: &BuildEnvironment) -> Vec<PathBuf> {
    let mut paths = vec![
        environment.java_home.join("bin/java"),
        environment.aapt2.clone(),
        environment
            .android_home
            .join("platforms/android-35/android.jar"),
    ];
    if let Some(distribution) = &environment.gradle_distribution {
        paths.push(distribution.join("lib/gradle-gradle-cli-main-9.5.0.jar"));
        paths.push(distribution.join("lib/agents/gradle-instrumentation-agent-9.5.0.jar"));
    }
    paths
}

fn hash_file_metadata(path: &Path, digest: &mut Sha256) -> Result<()> {
    let metadata = fs::metadata(path).with_context(|| format!("inspect {}", path.display()))?;
    let modified = metadata
        .modified()
        .ok()
        .and_then(|value| value.duration_since(UNIX_EPOCH).ok())
        .map(|value| value.as_nanos())
        .unwrap_or_default();
    digest.update(stable_toolchain_path(path).as_bytes());
    digest.update([0]);
    digest.update(metadata.len().to_le_bytes());
    digest.update(modified.to_le_bytes());
    Ok(())
}

fn stable_toolchain_path(path: &Path) -> String {
    normalize_termux_path_identity(path)
        .to_string_lossy()
        .into_owned()
}

fn hash_file_contents(path: &Path, digest: &mut Sha256) -> Result<()> {
    let mut file = File::open(path).with_context(|| format!("open {}", path.display()))?;
    let mut buffer = [0u8; 64 * 1024];
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        digest.update(&buffer[..read]);
    }
    digest.update([0]);
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
    use super::{hash_project_inputs, lookup, stable_toolchain_path, store};
    use sha2::{Digest, Sha256};
    use std::fs;

    fn fingerprint(root: &std::path::Path) -> String {
        let mut digest = Sha256::new();
        hash_project_inputs(root, &mut digest).unwrap();
        format!("{:x}", digest.finalize())
    }

    #[test]
    fn project_fingerprint_tracks_every_gradle_module_but_ignores_outputs() {
        let temp = tempfile::tempdir().unwrap();
        fs::create_dir_all(temp.path().join("plugin-app/src/main/java")).unwrap();
        fs::create_dir_all(temp.path().join("core-logic/src/main/java")).unwrap();
        fs::create_dir_all(temp.path().join("feature/nested/src/main/res/values")).unwrap();
        fs::write(temp.path().join("build.gradle"), "plugins {}\n").unwrap();
        fs::write(
            temp.path().join("settings.gradle"),
            "include ':plugin-app', ':core-logic', ':feature:nested'\n",
        )
        .unwrap();
        fs::write(
            temp.path().join("plugin-app/src/main/java/Main.java"),
            "class Main {}\n",
        )
        .unwrap();
        let core_source = temp.path().join("core-logic/src/main/java/Core.java");
        fs::write(&core_source, "class Core {}\n").unwrap();
        let nested_resource = temp
            .path()
            .join("feature/nested/src/main/res/values/strings.xml");
        fs::write(&nested_resource, "<resources/>\n").unwrap();
        let first = fingerprint(temp.path());

        for module in ["plugin-app", "core-logic", "feature/nested"] {
            fs::create_dir_all(temp.path().join(module).join("build/generated/src")).unwrap();
            fs::write(
                temp.path()
                    .join(module)
                    .join("build/generated/src/Generated.java"),
                "generated",
            )
            .unwrap();
        }
        assert_eq!(first, fingerprint(temp.path()));

        fs::write(&core_source, "class Core { int value; }\n").unwrap();
        assert_ne!(first, fingerprint(temp.path()));

        let after_core = fingerprint(temp.path());
        fs::write(
            &nested_resource,
            "<resources><string name=\"value\">changed</string></resources>\n",
        )
        .unwrap();
        assert_ne!(after_core, fingerprint(temp.path()));
    }

    #[test]
    fn project_fingerprint_tracks_module_build_logic_and_local_libraries() {
        let temp = tempfile::tempdir().unwrap();
        fs::create_dir_all(temp.path().join("core-logic/libs")).unwrap();
        let module_script = temp.path().join("core-logic/build.gradle.kts");
        let local_library = temp.path().join("core-logic/libs/runtime.jar");
        fs::write(&module_script, "plugins { java }\n").unwrap();
        fs::write(&local_library, "jar-v1").unwrap();
        let first = fingerprint(temp.path());

        fs::write(&module_script, "plugins { `java-library` }\n").unwrap();
        assert_ne!(first, fingerprint(temp.path()));
        let after_script = fingerprint(temp.path());

        fs::write(&local_library, "jar-v2").unwrap();
        assert_ne!(after_script, fingerprint(temp.path()));
    }

    #[test]
    fn toolchain_paths_normalize_the_termux_user_zero_alias() {
        assert_eq!(
            stable_toolchain_path(std::path::Path::new(
                "/data/data/com.termux/files/home/toolchain/bin/java"
            )),
            "/data/user/0/com.termux/files/home/toolchain/bin/java"
        );
        assert_eq!(
            stable_toolchain_path(std::path::Path::new(
                "/data/user/0/com.termux/files/home/toolchain/bin/java"
            )),
            "/data/user/0/com.termux/files/home/toolchain/bin/java"
        );
    }

    #[test]
    fn cache_requires_a_hash_identical_dist_artifact_for_builds() {
        let temp = tempfile::tempdir().unwrap();
        let package = temp.path().join("build/package/debug/plugin.shadowpkg");
        let dist = temp.path().join("dist/plugin.shadowpkg");
        fs::create_dir_all(package.parent().unwrap()).unwrap();
        fs::create_dir_all(dist.parent().unwrap()).unwrap();
        fs::write(&package, "package").unwrap();
        fs::write(&dist, "package").unwrap();
        let sha = crate::fsutil::sha256_file(&package).unwrap();
        store(
            temp.path(),
            "fingerprint".into(),
            1,
            "1.0.0".into(),
            &package,
            sha.clone(),
        )
        .unwrap();
        assert!(
            lookup(temp.path(), "fingerprint", 1, "1.0.0", true)
                .unwrap()
                .is_none()
        );
        store(
            temp.path(),
            "fingerprint".into(),
            1,
            "1.0.0".into(),
            &dist,
            sha,
        )
        .unwrap();
        assert_eq!(
            lookup(temp.path(), "fingerprint", 1, "1.0.0", true)
                .unwrap()
                .unwrap()
                .artifact,
            dist
        );
        fs::write(&dist, "tampered").unwrap();
        assert!(
            lookup(temp.path(), "fingerprint", 1, "1.0.0", true)
                .unwrap()
                .is_none()
        );
    }
}
