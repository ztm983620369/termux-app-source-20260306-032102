use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};
use regex::Regex;
use serde::Serialize;

use crate::context::{AppContext, PROJECT_CONFIG};
use crate::fsutil::write_atomic;

const JAVA_RESERVED_IDENTIFIERS: &[&str] = &[
    "_",
    "abstract",
    "assert",
    "boolean",
    "break",
    "byte",
    "case",
    "catch",
    "char",
    "class",
    "const",
    "continue",
    "default",
    "do",
    "double",
    "else",
    "enum",
    "exports",
    "extends",
    "false",
    "final",
    "finally",
    "float",
    "for",
    "goto",
    "if",
    "implements",
    "import",
    "instanceof",
    "int",
    "interface",
    "long",
    "module",
    "native",
    "new",
    "null",
    "open",
    "opens",
    "package",
    "permits",
    "private",
    "protected",
    "provides",
    "public",
    "record",
    "requires",
    "return",
    "sealed",
    "short",
    "static",
    "strictfp",
    "super",
    "switch",
    "synchronized",
    "this",
    "throw",
    "throws",
    "to",
    "transient",
    "transitive",
    "true",
    "try",
    "uses",
    "var",
    "void",
    "volatile",
    "when",
    "while",
    "with",
    "yield",
];

pub(crate) fn valid_java_identifier(value: &str) -> bool {
    let mut characters = value.chars();
    let Some(first) = characters.next() else {
        return false;
    };
    (first.is_ascii_alphabetic() || first == '_')
        && characters.all(|character| character.is_ascii_alphanumeric() || character == '_')
        && !JAVA_RESERVED_IDENTIFIERS.contains(&value)
}

pub(crate) fn valid_java_qualified_name(value: &str) -> bool {
    let segments = value.split('.').collect::<Vec<_>>();
    segments.len() >= 2 && segments.into_iter().all(valid_java_identifier)
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginConfig {
    pub schema_version: u32,
    pub plugin_slug: String,
    pub project_name: String,
    pub plugin_id: String,
    pub part_key: String,
    pub namespace: String,
    pub activity_class_name: String,
    pub resource_package_id: String,
    pub plugin_apk_name: String,
    pub bundle_base_name: String,
    pub display_name: String,
    pub description: String,
    pub default_version_code: u64,
    pub default_version_name: String,
    pub min_host_version_code: u64,
    pub max_host_version_code: u64,
    /// Optional Android Application class. Empty means the host uses the default Application.
    pub application_class_name: Option<String>,
    /// Java/Android resource expression consumed by the generated Shadow manifest.
    pub application_theme: String,
    pub activity_theme: String,
    pub screen_orientation: String,
    pub soft_input_mode: String,
    pub config_changes: String,
}

#[derive(Debug)]
pub struct ParsedProperties {
    pub values: BTreeMap<String, String>,
    pub duplicates: BTreeSet<String>,
}

impl PluginConfig {
    pub fn load(path: &Path) -> Result<Self> {
        let parsed = parse_properties(path)?;
        if !parsed.duplicates.is_empty() {
            bail!(
                "duplicate properties in {}: {}",
                path.display(),
                parsed.duplicates.into_iter().collect::<Vec<_>>().join(", ")
            );
        }
        let value = |key: &str| -> Result<String> {
            parsed
                .values
                .get(key)
                .filter(|value| !value.is_empty())
                .cloned()
                .with_context(|| format!("missing property {key} in {}", path.display()))
        };
        let optional = |key: &str, default: &str| -> String {
            parsed
                .values
                .get(key)
                .map(|value| value.trim())
                .filter(|value| !value.is_empty())
                .unwrap_or(default)
                .to_owned()
        };
        let schema_version = value("schemaVersion")?
            .parse()
            .context("parse schemaVersion")?;
        let positive = |key: &str| -> Result<u64> {
            let raw = value(key)?;
            let parsed = raw
                .parse::<u64>()
                .with_context(|| format!("{key} must be a positive integer: {raw}"))?;
            if parsed == 0 {
                bail!("{key} must be positive")
            }
            Ok(parsed)
        };
        Ok(Self {
            schema_version,
            plugin_slug: value("pluginSlug")?,
            project_name: value("projectName")?,
            plugin_id: value("pluginId")?,
            part_key: value("partKey")?,
            namespace: value("namespace")?,
            activity_class_name: value("activityClassName")?,
            resource_package_id: normalize_resource_id(&value("resourcePackageId")?)?,
            plugin_apk_name: value("pluginApkName")?,
            bundle_base_name: value("bundleBaseName")?,
            display_name: value("displayName")?,
            description: value("description")?,
            default_version_code: positive("defaultVersionCode")?,
            default_version_name: value("defaultVersionName")?,
            min_host_version_code: positive("minHostVersionCode")?,
            max_host_version_code: positive("maxHostVersionCode")?,
            application_class_name: parsed
                .values
                .get("applicationClassName")
                .map(|value| value.trim().to_owned())
                .filter(|value| !value.is_empty()),
            application_theme: optional(
                "applicationTheme",
                "android.R.style.Theme_Material_Light_NoActionBar",
            ),
            activity_theme: optional(
                "activityTheme",
                "android.R.style.Theme_Material_Light_NoActionBar",
            ),
            screen_orientation: optional("screenOrientation", "unspecified"),
            soft_input_mode: optional("softInputMode", "adjustNothing"),
            config_changes: optional("configChanges", "orientation|screenSize|keyboardHidden"),
        })
    }

    pub fn validate(&self) -> Vec<String> {
        let java_name = Regex::new(r"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$")
            .expect("valid regex");
        let mut failures = Vec::new();
        if !matches!(self.schema_version, 1 | 2) {
            failures.push(format!(
                "schemaVersion must be 1 or 2, got {}",
                self.schema_version
            ));
        }
        if !Regex::new(r"^[a-z][a-z0-9]*(?:-[a-z][a-z0-9]*)*$")
            .expect("valid regex")
            .is_match(&self.plugin_slug)
        {
            failures.push(format!("invalid pluginSlug: {}", self.plugin_slug));
        }
        if !Regex::new(r"^[A-Za-z][A-Za-z0-9_-]*$")
            .expect("valid regex")
            .is_match(&self.project_name)
        {
            failures.push(format!("invalid projectName: {}", self.project_name));
        }
        if !java_name.is_match(&self.plugin_id) {
            failures.push(format!("invalid pluginId: {}", self.plugin_id));
        }
        if !Regex::new(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
            .expect("valid regex")
            .is_match(&self.part_key)
        {
            failures.push(format!("invalid partKey: {}", self.part_key));
        }
        if !valid_java_qualified_name(&self.namespace) {
            failures.push(format!("invalid namespace: {}", self.namespace));
        }
        if !valid_java_qualified_name(&self.activity_class_name) {
            failures.push(format!(
                "invalid activityClassName: {}",
                self.activity_class_name
            ));
        } else if !self
            .activity_class_name
            .starts_with(&(self.namespace.clone() + "."))
        {
            failures.push("activityClassName must belong to namespace".to_owned());
        }
        if parse_resource_id(&self.resource_package_id).is_err() {
            failures.push(format!(
                "resourcePackageId must be in 0x02..0x7E: {}",
                self.resource_package_id
            ));
        }
        if !Regex::new(r"^[A-Za-z0-9][A-Za-z0-9._-]*\.apk$")
            .expect("valid regex")
            .is_match(&self.plugin_apk_name)
        {
            failures.push(format!("invalid pluginApkName: {}", self.plugin_apk_name));
        }
        if !Regex::new(r"^[a-z0-9][a-z0-9._-]*$")
            .expect("valid regex")
            .is_match(&self.bundle_base_name)
        {
            failures.push(format!("invalid bundleBaseName: {}", self.bundle_base_name));
        }
        if !valid_version_name(&self.default_version_name) {
            failures.push(format!(
                "invalid defaultVersionName: {}",
                self.default_version_name
            ));
        }
        if self.min_host_version_code > self.max_host_version_code {
            failures.push("minHostVersionCode exceeds maxHostVersionCode".to_owned());
        }
        if let Some(application) = &self.application_class_name
            && !valid_java_qualified_name(application)
        {
            failures.push(format!("invalid applicationClassName: {application}"));
        }
        let theme_pattern = Regex::new(
            r"^(?:android\.R\.style\.[A-Za-z_][A-Za-z0-9_]*|@style/[A-Za-z_][A-Za-z0-9_.]*|-?\d+|0[xX][0-9A-Fa-f]+)$",
        )
        .expect("valid theme regex");
        for (label, value) in [
            ("applicationTheme", self.application_theme.as_str()),
            ("activityTheme", self.activity_theme.as_str()),
        ] {
            if !theme_pattern.is_match(value) {
                failures.push(format!("invalid {label}: {value}"));
            }
        }
        if !matches!(
            self.screen_orientation.as_str(),
            "unspecified"
                | "portrait"
                | "landscape"
                | "user"
                | "behind"
                | "sensor"
                | "nosensor"
                | "sensorLandscape"
                | "sensorPortrait"
                | "reverseLandscape"
                | "reversePortrait"
                | "fullSensor"
                | "userLandscape"
                | "userPortrait"
                | "fullUser"
                | "locked"
        ) && !numeric_literal(&self.screen_orientation)
        {
            failures.push(format!(
                "invalid screenOrientation: {}",
                self.screen_orientation
            ));
        }
        let soft_input_parts = self
            .soft_input_mode
            .split('|')
            .map(str::trim)
            .collect::<Vec<_>>();
        let soft_input_numeric = unsigned_numeric_literal(&self.soft_input_mode);
        if !soft_input_parts.iter().all(|part| {
            matches!(
                *part,
                "adjustUnspecified"
                    | "adjustNothing"
                    | "adjustResize"
                    | "adjustPan"
                    | "stateUnspecified"
                    | "stateUnchanged"
                    | "stateHidden"
                    | "stateAlwaysHidden"
                    | "stateVisible"
                    | "stateAlwaysVisible"
            )
        }) && !soft_input_numeric
        {
            failures.push(format!("invalid softInputMode: {}", self.soft_input_mode));
        } else if !soft_input_numeric {
            let state_flags = soft_input_parts
                .iter()
                .filter(|part| part.starts_with("state"))
                .count();
            let adjust_flags = soft_input_parts
                .iter()
                .filter(|part| part.starts_with("adjust"))
                .count();
            if state_flags > 1 || adjust_flags > 1 {
                failures.push(format!(
                    "softInputMode may contain at most one state flag and one adjust flag: {}",
                    self.soft_input_mode
                ));
            }
        }
        if self.config_changes != "none"
            && !self.config_changes.split('|').all(|part| {
                matches!(
                    part.trim(),
                    "mcc"
                        | "mnc"
                        | "locale"
                        | "touchscreen"
                        | "keyboard"
                        | "keyboardHidden"
                        | "navigation"
                        | "orientation"
                        | "screenSize"
                        | "screenLayout"
                        | "uiMode"
                        | "fontScale"
                        | "smallestScreenSize"
                        | "layoutDirection"
                        | "colorMode"
                        | "density"
                        | "grammaticalGender"
                        | "fontWeightAdjustment"
                )
            })
            && !numeric_literal(&self.config_changes)
        {
            failures.push(format!("invalid configChanges: {}", self.config_changes));
        }
        for (label, value) in [
            ("displayName", self.display_name.as_str()),
            ("description", self.description.as_str()),
        ] {
            if value != value.trim() {
                failures.push(format!("{label} cannot start or end with whitespace"));
            }
            if value.contains('\\') || value.contains(['\n', '\r']) {
                failures.push(format!(
                    "{label} cannot contain Java properties escapes or line breaks"
                ));
            }
            if value.chars().any(|character| character.is_control()) {
                failures.push(format!("{label} cannot contain control characters"));
            }
        }
        failures
    }

    pub fn render(&self) -> String {
        format!(
            "# UTF-8 Shadow plugin identity. This is the only editable identity/config source.\n\
schemaVersion={}\n\
pluginSlug={}\n\
projectName={}\n\
pluginId={}\n\
partKey={}\n\
namespace={}\n\
activityClassName={}\n\
resourcePackageId={}\n\
pluginApkName={}\n\
bundleBaseName={}\n\
displayName={}\n\
description={}\n\
defaultVersionCode={}\n\
defaultVersionName={}\n\
minHostVersionCode={}\n\
maxHostVersionCode={}\n\
applicationClassName={}\n\
applicationTheme={}\n\
activityTheme={}\n\
screenOrientation={}\n\
softInputMode={}\n\
configChanges={}\n",
            self.schema_version,
            self.plugin_slug,
            self.project_name,
            self.plugin_id,
            self.part_key,
            self.namespace,
            self.activity_class_name,
            self.resource_package_id,
            self.plugin_apk_name,
            self.bundle_base_name,
            self.display_name,
            self.description,
            self.default_version_code,
            self.default_version_name,
            self.min_host_version_code,
            self.max_host_version_code,
            self.application_class_name.as_deref().unwrap_or(""),
            self.application_theme,
            self.activity_theme,
            self.screen_orientation,
            self.soft_input_mode,
            self.config_changes,
        )
    }

    pub fn write(&self, path: &Path) -> Result<()> {
        write_atomic(path, self.render().as_bytes())
    }
}

fn numeric_literal(value: &str) -> bool {
    let hexadecimal = (value.starts_with("0x") || value.starts_with("0X"))
        && value.len() > 2
        && value[2..]
            .chars()
            .all(|character| character.is_ascii_hexdigit());
    let decimal = value.strip_prefix('-').unwrap_or(value);
    hexadecimal
        || (!decimal.is_empty() && decimal.chars().all(|character| character.is_ascii_digit()))
}

fn unsigned_numeric_literal(value: &str) -> bool {
    ((value.starts_with("0x") || value.starts_with("0X"))
        && value.len() > 2
        && value[2..]
            .chars()
            .all(|character| character.is_ascii_hexdigit()))
        || (!value.is_empty() && value.chars().all(|character| character.is_ascii_digit()))
}

pub fn parse_properties(path: &Path) -> Result<ParsedProperties> {
    let text =
        fs::read_to_string(path).with_context(|| format!("read properties {}", path.display()))?;
    let mut values = BTreeMap::new();
    let mut duplicates = BTreeSet::new();
    for (line_number, line) in text.lines().enumerate() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') || trimmed.starts_with('!') {
            continue;
        }
        if line != trimmed {
            bail!(
                "property at {}:{} cannot start or end with whitespace",
                path.display(),
                line_number + 1
            );
        }
        let Some((key, value)) = trimmed.split_once('=') else {
            bail!(
                "invalid property at {}:{} (expected key=value)",
                path.display(),
                line_number + 1
            );
        };
        if key != key.trim() || value != value.trim() {
            bail!(
                "property at {}:{} cannot contain whitespace around '='",
                path.display(),
                line_number + 1
            );
        }
        if key.contains('\\') || value.contains('\\') {
            bail!(
                "property at {}:{} cannot use Java properties escapes",
                path.display(),
                line_number + 1
            );
        }
        let key = key.to_owned();
        let value = value.to_owned();
        if key.is_empty() {
            bail!(
                "empty property key at {}:{}",
                path.display(),
                line_number + 1
            );
        }
        if values.insert(key.clone(), value).is_some() {
            duplicates.insert(key);
        }
    }
    Ok(ParsedProperties { values, duplicates })
}

pub fn parse_resource_id(value: &str) -> Result<u8> {
    let digits = value
        .strip_prefix("0x")
        .or_else(|| value.strip_prefix("0X"))
        .context("resource ID must use 0xNN form")?;
    if digits.len() != 2 {
        bail!("resource ID must use exactly two hexadecimal digits")
    }
    let parsed = u8::from_str_radix(digits, 16).context("parse hexadecimal resource ID")?;
    if !(0x02..=0x7e).contains(&parsed) {
        bail!("resource ID is outside 0x02..0x7E")
    }
    Ok(parsed)
}

pub fn normalize_resource_id(value: &str) -> Result<String> {
    Ok(format!("0x{:02X}", parse_resource_id(value)?))
}

pub fn valid_version_name(value: &str) -> bool {
    Regex::new(r"^[A-Za-z0-9][A-Za-z0-9._+-]*$")
        .expect("valid regex")
        .is_match(value)
}

pub fn print_current(context: &AppContext) -> Result<()> {
    let project = context.project()?;
    let path = project.join(PROJECT_CONFIG);
    if context.json {
        let config = PluginConfig::load(&path)?;
        println!("{}", serde_json::to_string_pretty(&config)?);
    } else {
        print!("{}", fs::read_to_string(path)?);
    }
    Ok(())
}

pub fn sibling_configs(home: &Path) -> Result<Vec<(PathBuf, PluginConfig)>> {
    let mut configs = Vec::new();
    if !home.is_dir() {
        return Ok(configs);
    }
    for entry in fs::read_dir(home).with_context(|| format!("read {}", home.display()))? {
        let entry = entry?;
        if !entry.file_type()?.is_dir() {
            continue;
        }
        let name = entry.file_name();
        if !name.to_string_lossy().starts_with("termux-shadow-") {
            continue;
        }
        let path = entry.path().join(PROJECT_CONFIG);
        if path.is_file() {
            configs.push((entry.path(), PluginConfig::load(&path)?));
        }
    }
    configs.sort_by(|left, right| left.0.cmp(&right.0));
    Ok(configs)
}

#[cfg(test)]
mod tests {
    use super::{
        PluginConfig, normalize_resource_id, numeric_literal, parse_properties,
        valid_java_identifier, valid_java_qualified_name,
    };
    use std::fs;

    #[test]
    fn normalizes_resource_ids() {
        assert_eq!(normalize_resource_id("0x6a").unwrap(), "0x6A");
        assert!(normalize_resource_id("0x01").is_err());
        assert!(normalize_resource_id("7C").is_err());
    }

    #[test]
    fn rejects_reserved_java_identifiers_in_generated_class_names() {
        assert!(valid_java_identifier("notes"));
        assert!(!valid_java_identifier("import"));
        assert!(!valid_java_identifier("record"));
        assert!(valid_java_qualified_name("com.termux.shadow.notes"));
        assert!(!valid_java_qualified_name("com.termux.shadow.import"));
    }

    #[test]
    fn detects_duplicate_properties() {
        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("test.properties");
        fs::write(&path, "a=1\na=2\n").unwrap();
        let parsed = parse_properties(&path).unwrap();
        assert!(parsed.duplicates.contains("a"));
    }

    #[test]
    fn rejects_ambiguous_java_properties_syntax() {
        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("test.properties");
        fs::write(&path, "displayName=escaped\\ value\n").unwrap();
        assert!(parse_properties(&path).is_err());
        fs::write(&path, " displayName=leading-space\n").unwrap();
        assert!(parse_properties(&path).is_err());
    }

    #[test]
    fn numeric_literals_require_at_least_one_digit() {
        assert!(numeric_literal("-1"));
        assert!(numeric_literal("0x10"));
        assert!(!numeric_literal("-"));
        assert!(!numeric_literal("0x"));
    }

    #[test]
    fn renders_round_trip_config() {
        let config = PluginConfig {
            schema_version: 1,
            plugin_slug: "notes".into(),
            project_name: "TermuxShadowNotesPlugin".into(),
            plugin_id: "com.termux.shadow.notes".into(),
            part_key: "termux-notes-plugin".into(),
            namespace: "com.termux.shadow.notes".into(),
            activity_class_name: "com.termux.shadow.notes.NotesActivity".into(),
            resource_package_id: "0x7B".into(),
            plugin_apk_name: "termux-shadow-notes-plugin-debug.apk".into(),
            bundle_base_name: "termux-shadow-notes".into(),
            display_name: "Notes".into(),
            description: "Notes plugin".into(),
            default_version_code: 1,
            default_version_name: "1.0.0".into(),
            min_host_version_code: 118,
            max_host_version_code: 999_999,
            application_class_name: None,
            application_theme: "android.R.style.Theme_Material_Light_NoActionBar".into(),
            activity_theme: "android.R.style.Theme_Material_Light_NoActionBar".into(),
            screen_orientation: "unspecified".into(),
            soft_input_mode: "adjustNothing".into(),
            config_changes: "orientation|screenSize|keyboardHidden".into(),
        };
        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("shadow-plugin.properties");
        let mut invalid_soft_input = config.clone();
        invalid_soft_input.soft_input_mode = "adjustPan|adjustResize".into();
        assert!(
            invalid_soft_input
                .validate()
                .iter()
                .any(|message| message.contains("at most one"))
        );
        config.write(&path).unwrap();
        let loaded = PluginConfig::load(&path).unwrap();
        assert_eq!(loaded.plugin_id, config.plugin_id);
        assert!(loaded.validate().is_empty());
    }
}
