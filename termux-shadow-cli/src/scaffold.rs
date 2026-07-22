use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};
use regex::Regex;
use serde::Serialize;
use walkdir::WalkDir;

use crate::build;
use crate::cli::{BuildArgs, DoctorArgs, NewArgs, PublishArgs};
use crate::config::{PluginConfig, normalize_resource_id, sibling_configs};
use crate::context::AppContext;
use crate::doctor;
use crate::fsutil::copy_tree;
use crate::status::read_registry;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ScaffoldPlan {
    target: String,
    plugin_id: String,
    part_key: String,
    namespace: String,
    activity_class_name: String,
    resource_package_id: String,
    display_name: String,
    publish: bool,
}

pub fn run(context: &AppContext, args: NewArgs) -> Result<()> {
    validate_slug(&args.slug)?;
    validate_text(
        args.display_name.as_deref().unwrap_or_default(),
        "display name",
    )?;
    validate_text(
        args.description.as_deref().unwrap_or_default(),
        "description",
    )?;

    let template = context.template()?;
    let source_config = PluginConfig::load(&template.join("shadow-plugin.properties"))?;
    let pascal = pascal_case(&args.slug);
    let slug_dots = args.slug.replace('-', ".");
    let display_name = args.display_name.unwrap_or_else(|| pascal.clone());
    let plugin_id = args
        .plugin_id
        .unwrap_or_else(|| format!("com.termux.shadow.{slug_dots}"));
    let part_key = args
        .part_key
        .unwrap_or_else(|| format!("termux-{}-plugin", args.slug));
    let namespace = args
        .namespace
        .unwrap_or_else(|| format!("com.termux.shadow.{slug_dots}"));
    let activity_simple = args.activity.unwrap_or_else(|| format!("{pascal}Activity"));
    let activity_class_name = format!("{namespace}.{activity_simple}");
    let description = args
        .description
        .unwrap_or_else(|| format!("Termux Shadow plugin: {display_name}"));
    let target = resolve_target(context, &args.slug, args.target.as_deref(), args.dry_run)?;

    validate_derived_identity(&plugin_id, &part_key, &namespace, &activity_simple)?;
    let ownership = load_ownership(context)?;
    if let Some(owner) = ownership.plugin_ids.get(&plugin_id)
        && !args.allow_existing
    {
        bail!(
            "pluginId is already owned by {owner}; pass --allow-existing only when recovering its source"
        );
    }
    let resource_package_id = allocate_resource_id(
        &args.resource_id,
        &plugin_id,
        args.allow_existing,
        &ownership,
    )?;
    let config = PluginConfig {
        schema_version: 1,
        plugin_slug: args.slug.clone(),
        project_name: format!("TermuxShadow{pascal}Plugin"),
        plugin_id: plugin_id.clone(),
        part_key: part_key.clone(),
        namespace: namespace.clone(),
        activity_class_name: activity_class_name.clone(),
        resource_package_id: resource_package_id.clone(),
        plugin_apk_name: format!("termux-shadow-{}-plugin-debug.apk", args.slug),
        bundle_base_name: format!("termux-shadow-{}", args.slug),
        display_name: display_name.clone(),
        description,
        default_version_code: 1,
        default_version_name: "1.0.0".to_owned(),
        min_host_version_code: source_config.min_host_version_code,
        max_host_version_code: source_config.max_host_version_code,
    };
    let validation = config.validate();
    if !validation.is_empty() {
        bail!(
            "derived plugin identity is invalid: {}",
            validation.join("; ")
        );
    }
    let plan = ScaffoldPlan {
        target: target.display().to_string(),
        plugin_id,
        part_key,
        namespace,
        activity_class_name,
        resource_package_id,
        display_name,
        publish: args.publish,
    };
    if args.dry_run {
        emit_plan(context, &plan)?;
        return Ok(());
    }
    if !context.json {
        emit_plan(context, &plan)?;
    }
    if target.exists() {
        bail!("target already exists: {}", target.display());
    }
    let target_parent = target.parent().context("target has no parent")?;
    fs::create_dir_all(target_parent)
        .with_context(|| format!("create {}", target_parent.display()))?;
    let canonical_parent = fs::canonicalize(target_parent)?;
    let template = fs::canonicalize(template)?;
    if canonical_parent.starts_with(&template) {
        bail!("target cannot be inside the canonical template");
    }
    let target_name = target
        .file_name()
        .context("target has no directory name")?
        .to_string_lossy();
    let temporary = canonical_parent.join(format!(".{target_name}.new.{}", std::process::id()));
    if temporary.exists() {
        fs::remove_dir_all(&temporary)?;
    }
    fs::create_dir(&temporary)?;
    let guard = CleanupGuard::new(temporary.clone());

    copy_tree(&template, &temporary)?;
    config.write(&temporary.join("shadow-plugin.properties"))?;
    rewrite_business_source(&temporary, &source_config, &config, &activity_simple)?;
    if context.json {
        doctor::validate_for_project(context, &temporary, true, false)?;
    } else {
        doctor::run_for_project(
            context,
            &temporary,
            DoctorArgs {
                project_only: true,
                publish: false,
                full: false,
                fresh: false,
                failures_only: false,
            },
        )?;
    }
    fs::rename(&temporary, &target).with_context(|| {
        format!(
            "atomically commit scaffold {} to {}",
            temporary.display(),
            target.display()
        )
    })?;
    guard.commit();
    if !context.json {
        println!("\nCreated: {}", target.display());
    }

    if args.publish {
        if !context.json {
            println!("\nBuilding, publishing, reconciling, and confirming registration...");
        }
        build::run_publish(
            &context.with_project(target),
            PublishArgs {
                build: BuildArgs {
                    version_code: None,
                    version_name: None,
                    fresh: false,
                },
                no_wait: false,
                timeout: 45,
                allow_downgrade: false,
            },
        )?;
    } else if context.json {
        emit_plan(context, &plan)?;
    } else {
        println!("Next: cd {} && shadow-plugin dev", target.display());
    }
    Ok(())
}

#[derive(Debug, Default)]
struct Ownership {
    plugin_ids: BTreeMap<String, String>,
    resources: BTreeMap<String, ResourceOwner>,
}

#[derive(Debug)]
struct ResourceOwner {
    plugin_id: String,
    label: String,
}

fn load_ownership(context: &AppContext) -> Result<Ownership> {
    let mut ownership = Ownership::default();
    if context.shadow_home.join("reports/registry.json").is_file() {
        let registry = read_registry(&context.shadow_home)?;
        for plugin in registry.plugins {
            ownership.plugin_ids.insert(
                plugin.plugin_id.clone(),
                format!("live plugin {}", plugin.plugin_id),
            );
            for version in plugin.versions {
                if let Some(resource) = version
                    .manifest
                    .and_then(|manifest| manifest.resource_package_id)
                {
                    ownership.resources.insert(
                        resource.to_uppercase(),
                        ResourceOwner {
                            plugin_id: plugin.plugin_id.clone(),
                            label: format!("live plugin {}", plugin.plugin_id),
                        },
                    );
                }
            }
        }
    }
    for (root, config) in sibling_configs(&context.termux_home)? {
        ownership.plugin_ids.insert(
            config.plugin_id.clone(),
            format!("project {}", root.display()),
        );
        ownership.resources.insert(
            config.resource_package_id.to_uppercase(),
            ResourceOwner {
                plugin_id: config.plugin_id.clone(),
                label: format!("project {} ({})", root.display(), config.plugin_id),
            },
        );
    }
    Ok(ownership)
}

fn allocate_resource_id(
    requested: &str,
    plugin_id: &str,
    allow_existing: bool,
    ownership: &Ownership,
) -> Result<String> {
    if requested != "auto" {
        let normalized = normalize_resource_id(requested)?;
        if let Some(owner) = ownership.resources.get(&normalized.to_uppercase()) {
            let same_plugin = owner.plugin_id == plugin_id;
            if !(allow_existing && same_plugin) {
                bail!(
                    "resource package ID {normalized} is already owned by {}",
                    owner.label
                );
            }
        }
        return Ok(normalized);
    }
    let used = ownership
        .resources
        .keys()
        .map(|value| value.to_uppercase())
        .collect::<BTreeSet<_>>();
    for value in (0x02u8..=0x7bu8).rev().chain([0x7d, 0x7e]) {
        let candidate = format!("0x{value:02X}");
        if !used.contains(&candidate.to_uppercase()) {
            return Ok(candidate);
        }
    }
    bail!("no free resource package ID remains in 0x02..0x7E")
}

fn rewrite_business_source(
    project: &Path,
    old: &PluginConfig,
    new: &PluginConfig,
    new_activity_simple: &str,
) -> Result<()> {
    let old_activity_simple = old
        .activity_class_name
        .rsplit('.')
        .next()
        .context("template Activity has no simple class name")?;
    let source = project.join("plugin-app/src");
    for entry in WalkDir::new(&source).follow_links(false) {
        let entry = entry?;
        if !entry.file_type().is_file() {
            continue;
        }
        let extension = entry.path().extension().and_then(|value| value.to_str());
        if !matches!(extension, Some("java" | "kt" | "xml")) {
            continue;
        }
        let text = fs::read_to_string(entry.path())?;
        let rewritten = text
            .replace(&old.namespace, &new.namespace)
            .replace(old_activity_simple, new_activity_simple)
            .replace(&old.plugin_id, &new.plugin_id)
            .replace(&old.part_key, &new.part_key);
        if rewritten != text {
            fs::write(entry.path(), rewritten)?;
        }
    }

    let old_namespace_path = old.namespace.replace('.', "/");
    let new_namespace_path = new.namespace.replace('.', "/");
    for language in ["java", "kotlin"] {
        let old_directory = project.join(format!(
            "plugin-app/src/main/{language}/{old_namespace_path}"
        ));
        let new_directory = project.join(format!(
            "plugin-app/src/main/{language}/{new_namespace_path}"
        ));
        if old_directory.is_dir() && old_directory != new_directory {
            if let Some(parent) = new_directory.parent() {
                fs::create_dir_all(parent)?;
            }
            fs::rename(&old_directory, &new_directory)?;
            remove_empty_parents(
                old_directory.parent().unwrap_or(project),
                &project.join(format!("plugin-app/src/main/{language}")),
            )?;
        }
        for extension in ["java", "kt"] {
            let old_file = new_directory.join(format!("{old_activity_simple}.{extension}"));
            let new_file = new_directory.join(format!("{new_activity_simple}.{extension}"));
            if old_file.is_file() && old_file != new_file {
                fs::rename(old_file, new_file)?;
            }
        }
    }
    Ok(())
}

fn remove_empty_parents(start: &Path, boundary: &Path) -> Result<()> {
    let mut current = start.to_path_buf();
    while current.starts_with(boundary) && current != boundary {
        if fs::read_dir(&current)?.next().is_some() {
            break;
        }
        fs::remove_dir(&current)?;
        let Some(parent) = current.parent() else {
            break;
        };
        current = parent.to_path_buf();
    }
    Ok(())
}

fn resolve_target(
    context: &AppContext,
    slug: &str,
    requested: Option<&Path>,
    dry_run: bool,
) -> Result<PathBuf> {
    let target = requested
        .map(Path::to_path_buf)
        .unwrap_or_else(|| context.termux_home.join(format!("termux-shadow-{slug}")));
    if target.is_absolute() {
        return Ok(target);
    }
    if dry_run {
        return Ok(std::env::current_dir()?.join(target));
    }
    Ok(std::env::current_dir()?.join(target))
}

fn validate_slug(slug: &str) -> Result<()> {
    if Regex::new(r"^[a-z][a-z0-9-]*$")
        .expect("valid regex")
        .is_match(slug)
    {
        Ok(())
    } else {
        let suggestion = suggested_slug(slug);
        bail!(
            "SLUG_INVALID: slug {slug:?} must start with a lowercase letter and contain only lowercase letters, digits, and '-'; try `shadow-plugin new {suggestion}`"
        )
    }
}

fn suggested_slug(slug: &str) -> String {
    let mut output = String::new();
    let mut separator = false;
    for character in slug.trim().chars().flat_map(char::to_lowercase) {
        if character.is_ascii_lowercase() || character.is_ascii_digit() {
            if separator && !output.is_empty() {
                output.push('-');
            }
            output.push(character);
            separator = false;
        } else {
            separator = true;
        }
    }
    while output.ends_with('-') {
        output.pop();
    }
    if output.is_empty() {
        return "plugin".to_owned();
    }
    if !output.starts_with(|character: char| character.is_ascii_lowercase()) {
        output.insert_str(0, "plugin-");
    }
    output
}

fn validate_text(value: &str, label: &str) -> Result<()> {
    if value != value.trim() {
        bail!("{label} cannot start or end with whitespace");
    }
    if value.contains(['\n', '\r']) {
        bail!("{label} must be one line");
    }
    if value.contains('\\') {
        bail!("{label} cannot contain backslashes");
    }
    if value.chars().any(|character| character.is_control()) {
        bail!("{label} cannot contain control characters");
    }
    Ok(())
}

fn validate_derived_identity(
    plugin_id: &str,
    part_key: &str,
    namespace: &str,
    activity: &str,
) -> Result<()> {
    let java = Regex::new(r"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$")?;
    if !java.is_match(plugin_id) {
        bail!("invalid plugin ID: {plugin_id}");
    }
    if !Regex::new(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")?.is_match(part_key) {
        bail!("invalid part key: {part_key}");
    }
    if !java.is_match(namespace) {
        bail!("invalid namespace: {namespace}");
    }
    if !Regex::new(r"^[A-Za-z_][A-Za-z0-9_]*$")?.is_match(activity) {
        bail!("invalid Activity class: {activity}");
    }
    Ok(())
}

fn pascal_case(slug: &str) -> String {
    slug.split('-')
        .map(|part| {
            let mut characters = part.chars();
            match characters.next() {
                Some(first) => first.to_uppercase().collect::<String>() + characters.as_str(),
                None => String::new(),
            }
        })
        .collect()
}

fn emit_plan(context: &AppContext, plan: &ScaffoldPlan) -> Result<()> {
    if context.json {
        println!("{}", serde_json::to_string_pretty(plan)?);
    } else {
        println!("New Shadow plugin");
        println!("  target: {}", plan.target);
        println!("  pluginId: {}", plan.plugin_id);
        println!("  partKey: {}", plan.part_key);
        println!("  namespace: {}", plan.namespace);
        println!("  activity: {}", plan.activity_class_name);
        println!("  resource ID: {}", plan.resource_package_id);
        println!("  publish: {}", plan.publish);
    }
    Ok(())
}

struct CleanupGuard {
    path: PathBuf,
    committed: std::cell::Cell<bool>,
}

impl CleanupGuard {
    fn new(path: PathBuf) -> Self {
        Self {
            path,
            committed: std::cell::Cell::new(false),
        }
    }

    fn commit(&self) {
        self.committed.set(true);
    }
}

impl Drop for CleanupGuard {
    fn drop(&mut self) {
        if !self.committed.get() && self.path.is_dir() {
            let _ = fs::remove_dir_all(&self.path);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{Ownership, allocate_resource_id, pascal_case, suggested_slug, validate_slug};

    #[test]
    fn derives_pascal_activity_names() {
        assert_eq!(pascal_case("image-tools"), "ImageTools");
    }

    #[test]
    fn allocator_skips_reserved_standard_id() {
        let ownership = Ownership::default();
        assert_eq!(
            allocate_resource_id("auto", "com.termux.shadow.notes", false, &ownership).unwrap(),
            "0x7B"
        );
    }

    #[test]
    fn numeric_slugs_fail_early_with_a_valid_suggestion() {
        let error = validate_slug("071 Notes").unwrap_err().to_string();
        assert!(error.starts_with("SLUG_INVALID:"));
        assert!(error.contains("shadow-plugin new plugin-071-notes"));
        assert_eq!(suggested_slug("071 Notes"), "plugin-071-notes");
        assert!(validate_slug("plugin-071-notes").is_ok());
    }
}
