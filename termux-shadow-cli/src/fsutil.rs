use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};
use sha2::{Digest, Sha256};
use walkdir::{DirEntry, WalkDir};

pub fn write_atomic(path: &Path, bytes: &[u8]) -> Result<()> {
    let parent = path
        .parent()
        .with_context(|| format!("{} has no parent", path.display()))?;
    fs::create_dir_all(parent).with_context(|| format!("create {}", parent.display()))?;
    let temporary = parent.join(format!(
        ".{}.{}.tmp",
        path.file_name().unwrap_or_default().to_string_lossy(),
        std::process::id()
    ));
    let mut file = OpenOptions::new()
        .create(true)
        .truncate(true)
        .write(true)
        .open(&temporary)
        .with_context(|| format!("create {}", temporary.display()))?;
    set_private_permissions(&temporary)?;
    file.write_all(bytes)?;
    file.sync_all()?;
    drop(file);
    fs::rename(&temporary, path)
        .with_context(|| format!("atomically replace {}", path.display()))?;
    Ok(())
}

#[cfg(unix)]
pub fn set_private_permissions(path: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o600))
        .with_context(|| format!("set private permissions on {}", path.display()))
}

#[cfg(not(unix))]
pub fn set_private_permissions(_path: &Path) -> Result<()> {
    Ok(())
}

pub fn sha256_file(path: &Path) -> Result<String> {
    let mut file = File::open(path).with_context(|| format!("open {}", path.display()))?;
    let mut digest = Sha256::new();
    std::io::copy(&mut file, &mut digest)?;
    Ok(format!("{:x}", digest.finalize()))
}

pub fn sha256_paths(paths: &[PathBuf]) -> Result<String> {
    let mut digest = Sha256::new();
    for path in paths {
        digest.update(path.to_string_lossy().as_bytes());
        digest.update([0]);
        let bytes = fs::read(path).with_context(|| format!("read {}", path.display()))?;
        digest.update(&bytes);
        digest.update([0]);
    }
    Ok(format!("{:x}", digest.finalize()))
}

pub fn copy_tree(source: &Path, target: &Path) -> Result<()> {
    if !source.is_dir() {
        bail!("template directory not found: {}", source.display());
    }
    for entry in WalkDir::new(source)
        .follow_links(false)
        .into_iter()
        .filter_entry(|entry| !excluded(entry, source))
    {
        let entry = entry?;
        let relative = entry.path().strip_prefix(source)?;
        if relative.as_os_str().is_empty() {
            continue;
        }
        let destination = target.join(relative);
        if entry.file_type().is_dir() {
            fs::create_dir_all(&destination)?;
            copy_permissions(entry.path(), &destination)?;
        } else if entry.file_type().is_file() {
            if let Some(parent) = destination.parent() {
                fs::create_dir_all(parent)?;
            }
            fs::copy(entry.path(), &destination).with_context(|| {
                format!(
                    "copy template file {} to {}",
                    entry.path().display(),
                    destination.display()
                )
            })?;
            copy_permissions(entry.path(), &destination)?;
        } else if entry.file_type().is_symlink() {
            copy_symlink(entry.path(), &destination)?;
        }
    }
    Ok(())
}

fn excluded(entry: &DirEntry, root: &Path) -> bool {
    let Ok(relative) = entry.path().strip_prefix(root) else {
        return false;
    };
    let text = relative.to_string_lossy();
    text == ".gradle"
        || text.starts_with(".gradle/")
        || text == "build"
        || text.starts_with("build/")
        || text == "dist"
        || text.starts_with("dist/")
        || text == "plugin-app/build"
        || text.starts_with("plugin-app/build/")
        || text == "local.properties"
}

#[cfg(unix)]
fn copy_permissions(source: &Path, target: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    let mode = fs::metadata(source)?.permissions().mode();
    fs::set_permissions(target, fs::Permissions::from_mode(mode))?;
    Ok(())
}

#[cfg(not(unix))]
fn copy_permissions(_source: &Path, _target: &Path) -> Result<()> {
    Ok(())
}

#[cfg(unix)]
fn copy_symlink(source: &Path, target: &Path) -> Result<()> {
    use std::os::unix::fs::symlink;
    let link = fs::read_link(source)?;
    if link.is_absolute() {
        bail!("template contains absolute symlink: {}", source.display());
    }
    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent)?;
    }
    symlink(link, target)?;
    Ok(())
}

#[cfg(not(unix))]
fn copy_symlink(source: &Path, _target: &Path) -> Result<()> {
    bail!(
        "symlink in template is unsupported on this platform: {}",
        source.display()
    )
}

pub fn remove_dir_if_exists(path: &Path) -> Result<()> {
    if path.exists() {
        fs::remove_dir_all(path).with_context(|| format!("remove {}", path.display()))?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::write_atomic;

    #[cfg(unix)]
    #[test]
    fn atomic_writes_are_private() {
        use std::os::unix::fs::PermissionsExt;

        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("private.json");
        write_atomic(&path, b"{}\n").unwrap();
        assert_eq!(path.metadata().unwrap().permissions().mode() & 0o777, 0o600);
    }
}
