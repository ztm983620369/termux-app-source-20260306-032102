use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};
use regex::Regex;
use serde::Serialize;
use walkdir::WalkDir;

use crate::build;
use crate::cli::{BuildArgs, NewArgs, PublishArgs};
use crate::config::{
    PluginConfig, normalize_resource_id, sibling_configs, valid_java_identifier,
    valid_java_qualified_name,
};
use crate::context::AppContext;
use crate::dependency;
use crate::doctor;
use crate::fsutil::copy_tree;
use crate::status::read_registry;

const HANDOFF_CONTRACT_VERSION: u32 = 1;
const MAX_HANDOFF_FILES: usize = 512;
const MAX_KEY_SOURCE_BYTES: usize = 32 * 1024;
const MAX_KEY_SOURCES_BYTES: usize = 96 * 1024;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ScaffoldPlan {
    schema_version: u32,
    template: String,
    target: String,
    plugin_slug: String,
    project_name: String,
    plugin_id: String,
    part_key: String,
    namespace: String,
    activity_class_name: String,
    resource_package_id: String,
    plugin_apk_name: String,
    bundle_base_name: String,
    display_name: String,
    description: String,
    default_version_code: u64,
    default_version_name: String,
    min_host_version_code: u64,
    max_host_version_code: u64,
    publish: bool,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ScaffoldPreview<'a> {
    contract_version: u32,
    ok: bool,
    action: &'static str,
    status: &'static str,
    state_changed: bool,
    plan: &'a ScaffoldPlan,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ScaffoldOutput<'a> {
    contract_version: u32,
    ok: bool,
    action: &'static str,
    status: &'static str,
    state_changed: bool,
    plan: &'a ScaffoldPlan,
    lifecycle: ScaffoldLifecycle,
    handoff: &'a ScaffoldHandoff,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ScaffoldLifecycle {
    source: &'static str,
    registration: ScaffoldRegistration,
    runtime: &'static str,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ScaffoldRegistration {
    requested: bool,
    status: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    version_code: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    version_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    artifact_sha256: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    inbox_file: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ScaffoldHandoff {
    files: Vec<String>,
    edit_policy: EditPolicy,
    key_sources: KeySources,
    next_action: &'static str,
    next_command: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct EditPolicy {
    identity: &'static str,
    source: &'static str,
    dependencies: &'static str,
    managed_by_sync: [&'static str; 11],
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct KeySources {
    identity: TextSource,
    activity: TextSource,
    dependencies: TextSource,
    view_ids: TextSource,
    theme: TextSource,
    smoke_test: TextSource,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct TextSource {
    path: String,
    content: String,
}

pub fn run(context: &AppContext, args: NewArgs) -> Result<()> {
    run_internal(context, args, true)
}

pub fn run_silent(context: &AppContext, args: NewArgs) -> Result<()> {
    run_internal(context, args, false)
}

fn run_internal(context: &AppContext, args: NewArgs, emit: bool) -> Result<()> {
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
        .unwrap_or_else(|| namespace_for_slug(&args.slug));
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
        schema_version: 2,
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
        application_class_name: source_config.application_class_name.clone(),
        application_theme: source_config.application_theme.clone(),
        activity_theme: source_config.activity_theme.clone(),
        screen_orientation: source_config.screen_orientation.clone(),
        soft_input_mode: source_config.soft_input_mode.clone(),
        config_changes: source_config.config_changes.clone(),
    };
    let validation = config.validate();
    if !validation.is_empty() {
        bail!(
            "derived plugin identity is invalid: {}",
            validation.join("; ")
        );
    }
    let plan = ScaffoldPlan {
        schema_version: config.schema_version,
        template: template.display().to_string(),
        target: target.display().to_string(),
        plugin_slug: config.plugin_slug.clone(),
        project_name: config.project_name.clone(),
        plugin_id,
        part_key,
        namespace,
        activity_class_name,
        resource_package_id,
        plugin_apk_name: config.plugin_apk_name.clone(),
        bundle_base_name: config.bundle_base_name.clone(),
        display_name,
        description: config.description.clone(),
        default_version_code: config.default_version_code,
        default_version_name: config.default_version_name.clone(),
        min_host_version_code: config.min_host_version_code,
        max_host_version_code: config.max_host_version_code,
        publish: args.publish,
    };
    if args.dry_run {
        if emit {
            emit_preview(context, &plan)?;
        }
        return Ok(());
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
    doctor::validate_for_project(context, &temporary, true, false)?;
    if args.publish {
        let mut dependency_context = context.with_project(temporary.clone());
        dependency_context.verbose = false;
        dependency::ensure_lock(&dependency_context, &temporary)?;
    }
    let handoff = build_handoff(&temporary, &target, &config)?;
    fs::rename(&temporary, &target).with_context(|| {
        format!(
            "atomically commit scaffold {} to {}",
            temporary.display(),
            target.display()
        )
    })?;
    guard.commit();

    let registration = if args.publish {
        if emit && !context.json {
            println!(
                "new: source=CREATED registration=RUNNING project={}",
                target.display()
            );
        }
        let mut publish_context = context.with_project(target.clone());
        publish_context.verbose = false;
        let publish = build::execute_publish(
            &publish_context,
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
            false,
        )
        .with_context(|| {
            format!(
                "project source was created at {}, but publication or registration failed",
                target.display()
            )
        })?;
        ScaffoldRegistration::from_publish(&publish)
    } else {
        ScaffoldRegistration::not_requested()
    };
    if emit {
        emit_output(context, &plan, registration, &handoff)?;
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

pub(crate) fn validate_slug(slug: &str) -> Result<()> {
    if Regex::new(r"^[a-z][a-z0-9]*(?:-[a-z][a-z0-9]*)*$")
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

pub(crate) fn namespace_for_slug(slug: &str) -> String {
    let suffix = slug
        .split('-')
        .map(|segment| {
            if valid_java_identifier(segment) {
                segment.to_owned()
            } else {
                format!("{segment}_")
            }
        })
        .collect::<Vec<_>>()
        .join(".");
    format!("com.termux.shadow.{suffix}")
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
        .split('-')
        .map(|segment| {
            if segment.starts_with(|character: char| character.is_ascii_digit()) {
                format!("p{segment}")
            } else {
                segment.to_owned()
            }
        })
        .collect::<Vec<_>>()
        .join("-")
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
    if !valid_java_qualified_name(namespace) {
        bail!("invalid namespace: {namespace}");
    }
    if !valid_java_identifier(activity) {
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

impl ScaffoldRegistration {
    fn not_requested() -> Self {
        Self {
            requested: false,
            status: "NOT_REQUESTED",
            version_code: None,
            version_name: None,
            artifact_sha256: None,
            inbox_file: None,
        }
    }

    fn from_publish(output: &build::BuildOutput) -> Self {
        let artifact_sha256 = output
            .artifacts
            .first()
            .map(|artifact| artifact.sha256.clone());
        Self {
            requested: true,
            status: if output.registration_confirmed {
                "REGISTERED"
            } else {
                "PUBLISHED"
            },
            version_code: Some(output.version_code),
            version_name: Some(output.version_name.clone()),
            artifact_sha256,
            inbox_file: output
                .receipt
                .as_ref()
                .map(|receipt| receipt.file_name.clone()),
        }
    }
}

fn build_handoff(project: &Path, target: &Path, config: &PluginConfig) -> Result<ScaffoldHandoff> {
    let files = handoff_files(project)?;
    let activity_path = activity_source_path(project, config)?;
    let mut total_bytes = 0usize;
    let key_sources = KeySources {
        identity: read_key_source(project, "shadow-plugin.properties", &mut total_bytes)?,
        activity: read_key_source(project, &activity_path, &mut total_bytes)?,
        dependencies: read_key_source(project, "plugin-app/dependencies.gradle", &mut total_bytes)?,
        view_ids: read_key_source(
            project,
            "plugin-app/src/main/res/values/ids.xml",
            &mut total_bytes,
        )?,
        theme: read_key_source(
            project,
            "plugin-app/src/main/res/values/styles.xml",
            &mut total_bytes,
        )?,
        smoke_test: read_key_source(project, "shadow-smoke.json", &mut total_bytes)?,
    };
    Ok(ScaffoldHandoff {
        files,
        edit_policy: EditPolicy {
            identity: "shadow-plugin.properties",
            source: "plugin-app/src/**",
            dependencies: "plugin-app/dependencies.gradle",
            managed_by_sync: [
                "README.md",
                "build.gradle",
                "settings.gradle",
                "gradle.properties",
                "gradlew",
                "gradlew.bat",
                "shadow-plugin",
                "plugin-app/build.gradle",
                "gradle/**",
                "scripts/**",
                "shadow/**",
            ],
        },
        key_sources,
        next_action: "EDIT_SOURCE",
        next_command: format!("cd {} && shadow-plugin dev", shell_quote(target)?),
    })
}

fn handoff_files(project: &Path) -> Result<Vec<String>> {
    let entries = WalkDir::new(project)
        .follow_links(false)
        .into_iter()
        .filter_entry(|entry| include_handoff_entry(project, entry));
    let mut files = Vec::new();
    for entry in entries {
        let entry = entry?;
        if !entry.file_type().is_file() && !entry.file_type().is_symlink() {
            continue;
        }
        let relative = entry.path().strip_prefix(project)?;
        let relative = relative
            .to_str()
            .map(str::to_owned)
            .with_context(|| format!("non-UTF-8 scaffold path: {}", relative.display()))?;
        files.push(relative);
        if files.len() > MAX_HANDOFF_FILES {
            bail!(
                "SCAFFOLD_HANDOFF_TOO_LARGE: project has more than {MAX_HANDOFF_FILES} source files"
            );
        }
    }
    files.sort();
    Ok(files)
}

fn include_handoff_entry(project: &Path, entry: &walkdir::DirEntry) -> bool {
    let Ok(relative) = entry.path().strip_prefix(project) else {
        return false;
    };
    if relative.as_os_str().is_empty() {
        return true;
    }
    !relative.components().any(|component| {
        let std::path::Component::Normal(value) = component else {
            return false;
        };
        matches!(value.to_str(), Some(".gradle" | "build" | "dist" | ".cxx"))
    }) && relative != Path::new("local.properties")
}

fn activity_source_path(project: &Path, config: &PluginConfig) -> Result<String> {
    let class_path = config.activity_class_name.replace('.', "/");
    for (language, extension) in [("java", "java"), ("kotlin", "kt")] {
        let relative = format!("plugin-app/src/main/{language}/{class_path}.{extension}");
        if project.join(&relative).is_file() {
            return Ok(relative);
        }
    }
    bail!(
        "SCAFFOLD_HANDOFF_INVALID: configured Activity source is missing for {}",
        config.activity_class_name
    )
}

fn read_key_source(project: &Path, relative: &str, total_bytes: &mut usize) -> Result<TextSource> {
    let path = project.join(relative);
    let metadata = fs::symlink_metadata(&path)
        .with_context(|| format!("inspect key source {}", path.display()))?;
    if !metadata.file_type().is_file() {
        bail!("SCAFFOLD_HANDOFF_INVALID: key source must be a regular file: {relative}");
    }
    let file_bytes = usize::try_from(metadata.len()).unwrap_or(usize::MAX);
    if file_bytes > MAX_KEY_SOURCE_BYTES {
        bail!(
            "SCAFFOLD_HANDOFF_TOO_LARGE: key source {relative} is {} bytes; limit is {MAX_KEY_SOURCE_BYTES}",
            metadata.len()
        );
    }
    let next_total = total_bytes.saturating_add(file_bytes);
    if next_total > MAX_KEY_SOURCES_BYTES {
        bail!(
            "SCAFFOLD_HANDOFF_TOO_LARGE: key sources total {} bytes; limit is {MAX_KEY_SOURCES_BYTES}",
            next_total
        );
    }
    let bytes = fs::read(&path).with_context(|| format!("read key source {}", path.display()))?;
    if bytes.len() != file_bytes {
        bail!("SCAFFOLD_HANDOFF_CHANGED: key source changed while reading: {relative}");
    }
    *total_bytes = next_total;
    let content = String::from_utf8(bytes)
        .with_context(|| format!("key source is not UTF-8: {}", path.display()))?;
    Ok(TextSource {
        path: relative.to_owned(),
        content,
    })
}

fn shell_quote(path: &Path) -> Result<String> {
    let value = path
        .to_str()
        .with_context(|| format!("target path is not UTF-8: {}", path.display()))?;
    if !value.is_empty()
        && value.chars().all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '/' | '.' | '_' | '-')
        })
    {
        Ok(value.to_owned())
    } else {
        Ok(format!("'{}'", value.replace('\'', "'\"'\"'")))
    }
}

#[derive(Default)]
struct DisplayTree {
    children: BTreeMap<String, DisplayTree>,
}

fn render_tree(files: &[String]) -> Vec<String> {
    let mut root = DisplayTree::default();
    for file in files {
        let mut node = &mut root;
        for component in file.split('/') {
            node = node.children.entry(component.to_owned()).or_default();
        }
    }
    let mut lines = Vec::new();
    render_tree_children(&root, "", &mut lines);
    lines
}

fn render_tree_children(node: &DisplayTree, prefix: &str, lines: &mut Vec<String>) {
    let count = node.children.len();
    for (index, (name, child)) in node.children.iter().enumerate() {
        let last = index + 1 == count;
        let suffix = if child.children.is_empty() { "" } else { "/" };
        lines.push(format!(
            "{prefix}{}{}{}",
            if last { "└── " } else { "├── " },
            name,
            suffix
        ));
        let child_prefix = format!("{prefix}{}", if last { "    " } else { "│   " });
        render_tree_children(child, &child_prefix, lines);
    }
}

fn emit_preview(context: &AppContext, plan: &ScaffoldPlan) -> Result<()> {
    let preview = ScaffoldPreview {
        contract_version: HANDOFF_CONTRACT_VERSION,
        ok: true,
        action: "new",
        status: "PLANNED",
        state_changed: false,
        plan,
    };
    if context.json {
        println!(
            "{}",
            if context.verbose {
                serde_json::to_string_pretty(&preview)?
            } else {
                serde_json::to_string(&preview)?
            }
        );
    } else {
        println!("new: PLANNED (no writes)");
        println!("  target: {}", plan.target);
        println!("  pluginId: {}", plan.plugin_id);
        println!("  partKey: {}", plan.part_key);
        println!("  namespace: {}", plan.namespace);
        println!("  activity: {}", plan.activity_class_name);
        println!("  resource ID: {}", plan.resource_package_id);
        println!("  publish: {}", plan.publish);
        if context.verbose {
            println!("  template: {}", plan.template);
            println!("  schema: {}", plan.schema_version);
            println!("  slug: {}", plan.plugin_slug);
            println!("  Gradle project: {}", plan.project_name);
            println!("  plugin APK: {}", plan.plugin_apk_name);
            println!("  bundle base: {}", plan.bundle_base_name);
            println!("  display name: {}", plan.display_name);
            println!("  description: {}", plan.description);
            println!(
                "  default version: {} ({})",
                plan.default_version_name, plan.default_version_code
            );
            println!(
                "  Host compatibility: {}..={}",
                plan.min_host_version_code, plan.max_host_version_code
            );
        }
    }
    Ok(())
}

fn emit_output(
    context: &AppContext,
    plan: &ScaffoldPlan,
    registration: ScaffoldRegistration,
    handoff: &ScaffoldHandoff,
) -> Result<()> {
    let status = if registration.status == "REGISTERED" {
        "REGISTERED"
    } else if registration.status == "PUBLISHED" {
        "PUBLISHED"
    } else {
        "CREATED"
    };
    let output = ScaffoldOutput {
        contract_version: HANDOFF_CONTRACT_VERSION,
        ok: true,
        action: "new",
        status,
        state_changed: true,
        plan,
        lifecycle: ScaffoldLifecycle {
            source: "CREATED",
            registration,
            runtime: "UNPROVEN",
        },
        handoff,
    };
    if context.json {
        println!(
            "{}",
            if context.verbose {
                serde_json::to_string_pretty(&output)?
            } else {
                serde_json::to_string(&output)?
            }
        );
        return Ok(());
    }

    println!("new: {status}");
    println!("  project: {}", plan.target);
    println!(
        "  identity: pluginId={} partKey={} activity={} resource={}",
        plan.plugin_id, plan.part_key, plan.activity_class_name, plan.resource_package_id
    );
    println!("  source: {}", output.lifecycle.source);
    println!("  registration: {}", output.lifecycle.registration.status);
    if let Some(sha256) = &output.lifecycle.registration.artifact_sha256 {
        println!("  artifactSha256: {sha256}");
    }
    println!("  runtime: {}", output.lifecycle.runtime);

    println!("\nfiles ({}):", handoff.files.len());
    for line in render_tree(&handoff.files) {
        println!("  {line}");
    }
    println!("\nedit:");
    println!("  identity: {}", handoff.edit_policy.identity);
    println!("  source: {}", handoff.edit_policy.source);
    println!("  dependencies: {}", handoff.edit_policy.dependencies);
    println!(
        "  managed by sync: {}",
        handoff.edit_policy.managed_by_sync.join(", ")
    );
    print_key_source("identity", &handoff.key_sources.identity);
    print_key_source("activity", &handoff.key_sources.activity);
    print_key_source("dependencies", &handoff.key_sources.dependencies);
    print_key_source("viewIds", &handoff.key_sources.view_ids);
    print_key_source("theme", &handoff.key_sources.theme);
    print_key_source("smokeTest", &handoff.key_sources.smoke_test);
    println!("\nnextAction: {}", handoff.next_action);
    println!("after edit: {}", handoff.next_command);
    Ok(())
}

fn print_key_source(role: &str, source: &TextSource) {
    println!("\n--- {role}: {}", source.path);
    print!("{}", source.content);
    if !source.content.ends_with('\n') {
        println!();
    }
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
    use super::{
        Ownership, ScaffoldRegistration, allocate_resource_id, handoff_files, namespace_for_slug,
        pascal_case, read_key_source, render_tree, shell_quote, suggested_slug, validate_slug,
    };
    use std::fs;
    use std::path::Path;

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
        assert!(error.contains("shadow-plugin new plugin-p071-notes"));
        assert_eq!(suggested_slug("071 Notes"), "plugin-p071-notes");
        assert!(validate_slug("plugin-p071-notes").is_ok());
        assert!(validate_slug("plugin-071notes").is_err());
        assert!(validate_slug("plugin--notes").is_err());
    }

    #[test]
    fn derived_namespaces_escape_java_keywords() {
        assert_eq!(
            namespace_for_slug("import-record-notes"),
            "com.termux.shadow.import_.record_.notes"
        );
    }

    #[test]
    fn handoff_tree_is_complete_sorted_and_omits_generated_output() {
        let root = tempfile::tempdir().unwrap();
        fs::create_dir_all(root.path().join("plugin-app/src/main/java")).unwrap();
        fs::create_dir_all(root.path().join("plugin-app/build/generated")).unwrap();
        fs::create_dir_all(root.path().join(".gradle/cache")).unwrap();
        fs::write(root.path().join("README.md"), "readme\n").unwrap();
        fs::write(
            root.path().join("plugin-app/src/main/java/Main.java"),
            "class Main {}\n",
        )
        .unwrap();
        fs::write(
            root.path().join("plugin-app/build/generated/Main.class"),
            b"class",
        )
        .unwrap();
        fs::write(root.path().join(".gradle/cache/state.bin"), b"state").unwrap();
        fs::write(root.path().join("local.properties"), "sdk.dir=/tmp\n").unwrap();

        let files = handoff_files(root.path()).unwrap();
        assert_eq!(
            files,
            vec!["README.md", "plugin-app/src/main/java/Main.java",]
        );
        assert_eq!(
            render_tree(&files),
            vec![
                "├── README.md",
                "└── plugin-app/",
                "    └── src/",
                "        └── main/",
                "            └── java/",
                "                └── Main.java",
            ]
        );
    }

    #[test]
    fn next_command_shell_quotes_untrusted_paths() {
        assert_eq!(
            shell_quote(Path::new("/tmp/My Plugin's")).unwrap(),
            "'/tmp/My Plugin'\"'\"'s'"
        );
        assert_eq!(
            shell_quote(Path::new("/data/data/com.termux/files/home/game2048")).unwrap(),
            "/data/data/com.termux/files/home/game2048"
        );
    }

    #[test]
    fn unrequested_registration_is_explicit_without_null_noise() {
        assert_eq!(
            serde_json::to_value(ScaffoldRegistration::not_requested()).unwrap(),
            serde_json::json!({
                "requested": false,
                "status": "NOT_REQUESTED",
            })
        );
    }

    #[cfg(unix)]
    #[test]
    fn key_sources_must_not_be_symlinks() {
        use std::os::unix::fs::symlink;

        let root = tempfile::tempdir().unwrap();
        fs::write(root.path().join("outside.java"), "class Outside {}\n").unwrap();
        symlink("outside.java", root.path().join("Activity.java")).unwrap();
        let mut total_bytes = 0;
        let error = read_key_source(root.path(), "Activity.java", &mut total_bytes)
            .unwrap_err()
            .to_string();
        assert!(error.contains("must be a regular file"));
    }
}
