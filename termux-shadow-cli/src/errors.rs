use std::collections::HashSet;
use std::error::Error;
use std::fmt;
use std::path::Path;

use anyhow::Error as AnyError;
use regex::Regex;
use serde::Serialize;

use crate::cli::DependencyPolicy;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Diagnostic {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub file: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub line: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub column: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub activity: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error_type: Option<String>,
    pub message: String,
}

#[derive(Debug)]
pub struct GradleFailure {
    pub phase: String,
    pub code: String,
    pub message: String,
    pub diagnostics: Vec<Diagnostic>,
    pub log_path: String,
}

impl GradleFailure {
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn classify(output: &str, project: &Path, log_path: &Path) -> Self {
        Self::classify_with_policy(output, project, log_path, DependencyPolicy::Offline, false)
    }

    pub fn classify_with_policy(
        output: &str,
        project: &Path,
        log_path: &Path,
        policy: DependencyPolicy,
        allow_network: bool,
    ) -> Self {
        let java = java_diagnostics(output, project);
        let (code, phase, diagnostics, message) = if !java.is_empty() {
            (
                "JAVA_COMPILE_ERROR",
                "compileJava",
                java,
                "Java compilation failed",
            )
        } else if android_library_plugin_failure(output) {
            let missing_version = contains_any(
                output,
                &[
                    "plugin dependency must include a version number",
                    "plugin version is required",
                ],
            );
            let offline = matches!(policy, DependencyPolicy::Offline)
                || (matches!(policy, DependencyPolicy::CacheFirst) && !allow_network);
            (
                if missing_version {
                    "ANDROID_LIBRARY_PLUGIN_UNDECLARED"
                } else if offline {
                    "ANDROID_LIBRARY_PLUGIN_NOT_IN_CACHE"
                } else {
                    "ANDROID_LIBRARY_PLUGIN_RESOLUTION_FAILED"
                },
                "resolvePlugins",
                android_library_diagnostics(output, project, missing_version),
                if missing_version {
                    "Android Library plugin has no centrally declared version"
                } else if offline {
                    "The declared Android Library plugin version is unavailable in the selected cache"
                } else {
                    "The declared Android Library plugin version could not be resolved from configured repositories"
                },
            )
        } else if contains_any(
            output,
            &[
                "Manifest merger failed",
                "processDebugMainManifest FAILED",
                "uses-sdk:minSdkVersion",
            ],
        ) {
            (
                "MANIFEST_MERGE_ERROR",
                "mergeManifest",
                generic_diagnostics(output, &["error:", "Manifest merger failed"]),
                "Android manifest merge failed",
            )
        } else if contains_any(
            output,
            &[
                "Android resource linking failed",
                "AAPT: error:",
                "aapt2 error",
            ],
        ) {
            (
                "ANDROID_RESOURCE_ERROR",
                "linkResources",
                generic_diagnostics(output, &["error:", "Android resource linking failed"]),
                "Android resource compilation or linking failed",
            )
        } else if contains_any(
            output,
            &[
                "Could not resolve all files",
                "Could not resolve all dependencies",
                "Could not find ",
                "No cached version of",
                "Could not determine the dependencies",
            ],
        ) {
            dependency_failure(output, policy, allow_network)
        } else if contains_any(
            output,
            &[
                "DexArchiveMergerException",
                "mergeExtDex",
                "mergeDex",
                "D8:",
            ],
        ) {
            (
                "DEX_ERROR",
                "dex",
                generic_diagnostics(output, &["error:", "DexArchiveMergerException", "D8:"]),
                "DEX generation failed",
            )
        } else if contains_any(output, &["Compilation error.", "compileDebugKotlin FAILED"]) {
            (
                "KOTLIN_COMPILE_ERROR",
                "compileKotlin",
                generic_diagnostics(output, &[" e: ", "error:"]),
                "Kotlin compilation failed",
            )
        } else {
            (
                "GRADLE_ERROR",
                infer_phase(output),
                generic_diagnostics(output, &["* What went wrong:", "error:"]),
                "Gradle task failed",
            )
        };
        Self {
            phase: phase.to_owned(),
            code: code.to_owned(),
            message: message.to_owned(),
            diagnostics,
            log_path: display_path(project, log_path),
        }
    }
}

fn dependency_failure(
    output: &str,
    policy: DependencyPolicy,
    allow_network: bool,
) -> (&'static str, &'static str, Vec<Diagnostic>, &'static str) {
    let mut diagnostics = generic_diagnostics(output, &["Could not find ", "No cached version of"]);
    if matches!(policy, DependencyPolicy::Offline)
        || (matches!(policy, DependencyPolicy::CacheFirst) && !allow_network)
    {
        diagnostics.insert(0, Diagnostic {
            kind: Some("DEPENDENCY_REMEDIATION".to_owned()),
            file: None,
            line: None,
            column: None,
            activity: None,
            error_type: None,
            message: "Inspect with `shadow-plugin deps status`; then use `shadow-plugin deps resolve --allow-network --lock`, `shadow-plugin deps import-gradle-cache --from PATH`, or a reviewed project vendor cache".to_owned(),
        });
        (
            "DEPENDENCY_NOT_IN_CACHE",
            "resolveDependencies",
            diagnostics,
            "A required dependency is not available in the selected cache",
        )
    } else {
        diagnostics.insert(0, Diagnostic {
            kind: Some("DEPENDENCY_REMEDIATION".to_owned()),
            file: None,
            line: None,
            column: None,
            activity: None,
            error_type: None,
            message: "Verify repository configuration and connectivity, then rerun `shadow-plugin deps resolve --allow-network --lock`".to_owned(),
        });
        (
            "DEPENDENCY_RESOLUTION_FAILED",
            "resolveDependencies",
            diagnostics,
            "Gradle could not resolve one or more dependencies from the configured repositories",
        )
    }
}

impl fmt::Display for GradleFailure {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            formatter,
            "{}: {} (phase={}; log={})",
            self.code, self.message, self.phase, self.log_path
        )?;
        if let Some(first) = self.diagnostics.first() {
            write!(formatter, ": ")?;
            if let Some(file) = &first.file {
                write!(formatter, "{file}")?;
                if let Some(line) = first.line {
                    write!(formatter, ":{line}")?;
                }
                write!(formatter, ": ")?;
            }
            write!(formatter, "{}", first.message)?;
        }
        Ok(())
    }
}

impl Error for GradleFailure {}

#[derive(Debug)]
pub struct RuntimeActivationFailure {
    pub action: &'static str,
    pub plugin_id: String,
    pub status: String,
    pub generation: Option<String>,
    pub operation_id: Option<String>,
    pub message: String,
    pub diagnostics: Vec<Diagnostic>,
    pub log_path: Option<String>,
    pub state_changed: bool,
}

impl fmt::Display for RuntimeActivationFailure {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            formatter,
            "{} failed for {}: status={} error={}",
            self.action, self.plugin_id, self.status, self.message
        )
    }
}

impl Error for RuntimeActivationFailure {}

#[derive(Debug)]
pub struct PreflightFailure {
    pub code: String,
    pub message: String,
    pub diagnostics: Vec<Diagnostic>,
}

impl fmt::Display for PreflightFailure {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{}: {}", self.code, self.message)
    }
}

impl Error for PreflightFailure {}

pub fn preflight_failure(
    code: impl Into<String>,
    message: impl Into<String>,
    file: Option<String>,
) -> AnyError {
    let code = code.into();
    let message = message.into();
    AnyError::new(PreflightFailure {
        code,
        diagnostics: vec![Diagnostic {
            kind: Some("PREFLIGHT".to_owned()),
            file,
            line: None,
            column: None,
            activity: None,
            error_type: None,
            message: message.clone(),
        }],
        message,
    })
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DevelopmentVersion {
    pub version_code: Option<u64>,
    pub version_name: Option<String>,
    pub generation: String,
    pub sha256: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DevelopmentContext {
    project: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    current_healthy: Option<DevelopmentVersion>,
    #[serde(skip_serializing_if = "Option::is_none")]
    next_version_code: Option<u64>,
    active_changed: bool,
    next_action: String,
    resume_command: &'static str,
}

#[derive(Debug)]
struct DevelopmentFailure {
    phase: String,
    code: String,
    retryable: bool,
    message: String,
    diagnostics: Vec<Diagnostic>,
    state_changed: Option<bool>,
    log_path: Option<String>,
    plugin_id: Option<String>,
    status: Option<String>,
    generation: Option<String>,
    operation_id: Option<String>,
    context: DevelopmentContext,
}

impl fmt::Display for DevelopmentFailure {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{}: {}", self.code, self.message)
    }
}

impl Error for DevelopmentFailure {}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ErrorEnvelope<'a> {
    ok: bool,
    action: &'a str,
    phase: String,
    code: String,
    retryable: bool,
    message: String,
    diagnostics: Vec<Diagnostic>,
    state_changed: Option<bool>,
    log_path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    plugin_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    status: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    generation: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[serde(rename = "hostOperationId")]
    operation_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    context: Option<DevelopmentContext>,
}

pub fn with_development_context(
    error: AnyError,
    plugin_id: Option<String>,
    project: String,
    current_healthy: Option<DevelopmentVersion>,
    next_version_code: Option<u64>,
) -> AnyError {
    let base = envelope(&error, "dev");
    let next_action = if base.retryable {
        "RETRY_DEV"
    } else {
        "FIX_AND_RERUN_DEV"
    };
    AnyError::new(DevelopmentFailure {
        phase: base.phase,
        code: base.code,
        retryable: base.retryable,
        message: base.message,
        diagnostics: base.diagnostics,
        state_changed: base.state_changed.or(Some(false)),
        log_path: base.log_path,
        plugin_id: plugin_id.or(base.plugin_id),
        status: base.status,
        generation: base.generation,
        operation_id: base.operation_id,
        context: DevelopmentContext {
            project,
            current_healthy,
            next_version_code,
            active_changed: false,
            next_action: next_action.to_owned(),
            resume_command: "shadow-plugin dev",
        },
    })
}

pub fn emit(error: &AnyError, json: bool, action: &str) {
    let envelope = envelope(error, action);
    if json {
        match serde_json::to_string(&envelope) {
            Ok(value) => println!("{value}"),
            Err(serialize_error) => {
                eprintln!("shadow-plugin: failed to serialize error response: {serialize_error}")
            }
        }
    } else {
        eprintln!("shadow-plugin: {}: {}", envelope.code, envelope.message);
        if let Some(diagnostic) = envelope.diagnostics.first() {
            if let Some(file) = &diagnostic.file {
                eprint!("  {file}");
                if let Some(line) = diagnostic.line {
                    eprint!(":{line}");
                }
                eprint!(": ");
            } else {
                eprint!("  ");
            }
            eprintln!("{}", diagnostic.message);
        }
        if let Some(log_path) = envelope.log_path {
            eprintln!("  log: {log_path}");
        }
        if let Some(context) = envelope.context {
            if let Some(healthy) = context.current_healthy {
                eprintln!(
                    "  current healthy: {} (code {}) generation={}",
                    healthy.version_name.as_deref().unwrap_or("unknown"),
                    healthy
                        .version_code
                        .map(|value| value.to_string())
                        .as_deref()
                        .unwrap_or("unknown"),
                    healthy.generation
                );
            } else {
                eprintln!("  current healthy: none");
            }
            eprintln!("  active changed: {}", context.active_changed);
            if let Some(next) = context.next_version_code {
                eprintln!("  next version code: {next}");
            }
            eprintln!("  next: {}", context.next_action);
            eprintln!("  resume: {}", context.resume_command);
        }
    }
}

pub fn code(error: &AnyError) -> String {
    envelope(error, "command").code
}

pub fn emit_usage(message: String, json: bool, action: &str) {
    let envelope = ErrorEnvelope {
        ok: false,
        action,
        phase: "arguments".to_owned(),
        code: "CLI_USAGE_ERROR".to_owned(),
        retryable: false,
        message,
        diagnostics: Vec::new(),
        state_changed: Some(false),
        log_path: None,
        plugin_id: None,
        status: None,
        generation: None,
        operation_id: None,
        context: None,
    };
    if json {
        println!(
            "{}",
            serde_json::to_string(&envelope)
                .unwrap_or_else(|_| "{\"ok\":false,\"code\":\"CLI_USAGE_ERROR\"}".to_owned())
        );
    } else {
        eprintln!("{}", envelope.message);
    }
}

fn envelope<'a>(error: &AnyError, action: &'a str) -> ErrorEnvelope<'a> {
    if let Some(development) = error.downcast_ref::<DevelopmentFailure>() {
        return ErrorEnvelope {
            ok: false,
            action,
            phase: development.phase.clone(),
            code: development.code.clone(),
            retryable: development.retryable,
            message: development.message.clone(),
            diagnostics: development.diagnostics.clone(),
            state_changed: development.state_changed,
            log_path: development.log_path.clone(),
            plugin_id: development.plugin_id.clone(),
            status: development.status.clone(),
            generation: development.generation.clone(),
            operation_id: development.operation_id.clone(),
            context: Some(development.context.clone()),
        };
    }
    if let Some(preflight) = error.downcast_ref::<PreflightFailure>() {
        return ErrorEnvelope {
            ok: false,
            action,
            phase: "preflight".to_owned(),
            code: preflight.code.clone(),
            retryable: false,
            message: preflight.message.clone(),
            diagnostics: preflight.diagnostics.clone(),
            state_changed: Some(false),
            log_path: None,
            plugin_id: None,
            status: None,
            generation: None,
            operation_id: None,
            context: None,
        };
    }
    if let Some(gradle) = error.downcast_ref::<GradleFailure>() {
        return ErrorEnvelope {
            ok: false,
            action,
            phase: gradle.phase.clone(),
            code: gradle.code.clone(),
            retryable: matches!(
                gradle.code.as_str(),
                "DEPENDENCY_RESOLUTION_FAILED" | "ANDROID_LIBRARY_PLUGIN_RESOLUTION_FAILED"
            ),
            message: gradle.message.clone(),
            diagnostics: gradle.diagnostics.clone(),
            state_changed: Some(false),
            log_path: Some(gradle.log_path.clone()),
            plugin_id: None,
            status: None,
            generation: None,
            operation_id: None,
            context: None,
        };
    }
    if let Some(runtime) = error.downcast_ref::<RuntimeActivationFailure>() {
        return ErrorEnvelope {
            ok: false,
            action,
            phase: "runtimeHealth".to_owned(),
            code: "ACTIVATION_FAILED".to_owned(),
            retryable: true,
            message: runtime.message.clone(),
            diagnostics: runtime.diagnostics.clone(),
            state_changed: Some(runtime.state_changed),
            log_path: runtime.log_path.clone(),
            plugin_id: Some(runtime.plugin_id.clone()),
            status: Some(runtime.status.clone()),
            generation: runtime.generation.clone(),
            operation_id: runtime.operation_id.clone(),
            context: None,
        };
    }
    let message = format!("{error:#}");
    let prefixed_code = message
        .split_once(':')
        .map(|(prefix, _)| prefix)
        .filter(|prefix| {
            !prefix.is_empty()
                && prefix
                    .chars()
                    .all(|character| character.is_ascii_uppercase() || character == '_')
        });
    let (phase, code) =
        if message.contains("SLUG_INVALID:") || message.contains("VERSION_NAME_INVALID:") {
            (
                "arguments",
                prefixed_code.unwrap_or("CLI_USAGE_ERROR").to_owned(),
            )
        } else if message.contains("VERSION_REQUIRED:") {
            ("versionGuard", "VERSION_REQUIRED".to_owned())
        } else if message.contains("VERSION_NOT_INCREASING:") {
            ("versionGuard", "VERSION_NOT_INCREASING".to_owned())
        } else if message.contains("DOWNGRADE_BLOCKED:") {
            ("versionGuard", "DOWNGRADE_BLOCKED".to_owned())
        } else if message.contains("LAUNCH_BUSY:") {
            ("runtimeAdmission", "LAUNCH_BUSY".to_owned())
        } else if message.contains("no retained rollback generation")
            || message.contains("no runtime-verified healthy rollback generation")
        {
            ("runtimeAdmission", "ROLLBACK_NOT_AVAILABLE".to_owned())
        } else if message.contains("WORKER_") {
            (
                "workerProtocol",
                prefixed_code.unwrap_or("WORKER_UNAVAILABLE").to_owned(),
            )
        } else if message.contains("ACTIVITY_SOURCE_MISSING") {
            ("preflight", "ACTIVITY_SOURCE_MISSING".to_owned())
        } else if let Some(code) = prefixed_code {
            ("preflight", code.to_owned())
        } else if message.contains("timed out") {
            ("wait", "TIMEOUT".to_owned())
        } else {
            ("command", "COMMAND_FAILED".to_owned())
        };
    ErrorEnvelope {
        ok: false,
        action,
        phase: phase.to_owned(),
        code: code.clone(),
        retryable: matches!(
            code.as_str(),
            "TIMEOUT"
                | "LAUNCH_BUSY"
                | "WORKER_UNAVAILABLE"
                | "WORKER_PROTOCOL_MISMATCH"
                | "WORKER_REQUEST_FAILED"
        ),
        message: message
            .split_once(": ")
            .filter(|(prefix, _)| {
                prefix
                    .chars()
                    .all(|character| character.is_ascii_uppercase() || character == '_')
            })
            .map(|(_, detail)| detail.to_owned())
            .unwrap_or(message),
        diagnostics: Vec::new(),
        state_changed: if matches!(
            phase,
            "arguments" | "preflight" | "versionGuard" | "workerProtocol"
        ) || matches!(action, "build" | "doctor" | "info" | "status")
            || matches!(
                code.as_str(),
                "VERSION_REQUIRED"
                    | "VERSION_NOT_INCREASING"
                    | "DOWNGRADE_BLOCKED"
                    | "LAUNCH_BUSY"
                    | "ROLLBACK_NOT_AVAILABLE"
                    | "WORKER_UNAVAILABLE"
                    | "WORKER_PROTOCOL_MISMATCH"
                    | "WORKER_REQUEST_ID_CONFLICT"
                    | "WORKER_REQUEST_FAILED"
            ) {
            Some(false)
        } else {
            None
        },
        log_path: None,
        plugin_id: None,
        status: None,
        generation: None,
        operation_id: None,
        context: None,
    }
}

fn java_diagnostics(output: &str, project: &Path) -> Vec<Diagnostic> {
    let pattern = Regex::new(
        r"(?m)^\s*(?P<file>[^\r\n]+\.java):(?P<line>\d+):(?:(?P<column>\d+):)?\s*error:\s*(?P<message>[^\r\n]+)",
    )
    .expect("valid Java diagnostic regex");
    let lines = output.lines().collect::<Vec<_>>();
    let mut result = Vec::new();
    let mut seen = HashSet::new();
    for capture in pattern.captures_iter(output).take(16) {
        let raw_file = capture.name("file").map(|value| value.as_str().trim());
        let mut message = capture
            .name("message")
            .map(|value| value.as_str().trim().to_owned())
            .unwrap_or_else(|| "Java compiler error".to_owned());
        if message == "cannot find symbol"
            && let Some(full_match) = capture.get(0)
        {
            let line_number = output[..full_match.start()].lines().count();
            for continuation in lines.iter().skip(line_number + 1).take(4) {
                let trimmed = continuation.trim();
                if let Some(symbol) = trimmed.strip_prefix("symbol:") {
                    message.push_str(": ");
                    message.push_str(symbol.trim());
                    break;
                }
            }
        }
        let file = raw_file.map(|path| display_source_path(project, path));
        let line = capture
            .name("line")
            .and_then(|value| value.as_str().parse().ok());
        let column = capture
            .name("column")
            .and_then(|value| value.as_str().parse().ok());
        if seen.insert((file.clone(), line, column, message.clone())) {
            result.push(Diagnostic {
                kind: None,
                file,
                line,
                column,
                activity: None,
                error_type: None,
                message,
            });
        }
    }
    result
}

fn generic_diagnostics(output: &str, needles: &[&str]) -> Vec<Diagnostic> {
    output
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && needles.iter().any(|needle| line.contains(needle)))
        .take(8)
        .map(|line| Diagnostic {
            kind: None,
            file: None,
            line: None,
            column: None,
            activity: None,
            error_type: None,
            message: line.chars().take(512).collect(),
        })
        .collect()
}

fn android_library_plugin_failure(output: &str) -> bool {
    contains_any(
        output,
        &[
            "Plugin [id: 'com.android.library'",
            "Plugin [id: \"com.android.library\"",
            "com.android.library.gradle.plugin",
            "Plugin with id 'com.android.library' not found",
        ],
    )
}

fn android_library_diagnostics(
    output: &str,
    project: &Path,
    missing_version: bool,
) -> Vec<Diagnostic> {
    let location = Regex::new(r"Build file '([^']+)' line: (\d+)")
        .expect("valid Gradle build-file location regex")
        .captures(output);
    let file = location
        .as_ref()
        .and_then(|captures| captures.get(1))
        .map(|path| display_source_path(project, path.as_str()));
    let line = location
        .as_ref()
        .and_then(|captures| captures.get(2))
        .and_then(|line| line.as_str().parse().ok());
    let message = if missing_version {
        "Declare `id 'com.android.library' version '<same cached AGP version as com.android.application>' apply false` in the root plugins block and map that ID to `com.android.tools.build:gradle:${requested.version}` in pluginManagement when the offline marker is absent; then rerun `shadow-plugin dev`"
    } else {
        "The portable cache has the AGP module but may lack the Library plugin marker; map `com.android.library` to `com.android.tools.build:gradle:${requested.version}` with pluginManagement resolutionStrategy, or add the marker artifact, then rerun `shadow-plugin dev`"
    };
    vec![Diagnostic {
        kind: Some("GRADLE_PLUGIN_RESOLUTION".to_owned()),
        file,
        line,
        column: None,
        activity: None,
        error_type: None,
        message: message.to_owned(),
    }]
}

fn infer_phase(output: &str) -> &'static str {
    if output.contains("compileDebugJavaWithJavac") {
        "compileJava"
    } else if output.contains("processDebugResources") || output.contains("linkDebug") {
        "resources"
    } else if output.contains("assembleShadowPluginDebug") {
        "package"
    } else if output.contains("publishShadowPluginDebug") {
        "publish"
    } else {
        "gradle"
    }
}

fn contains_any(value: &str, needles: &[&str]) -> bool {
    needles.iter().any(|needle| value.contains(needle))
}

fn display_source_path(project: &Path, raw: &str) -> String {
    let path = Path::new(raw);
    path.strip_prefix(project)
        .unwrap_or(path)
        .to_string_lossy()
        .into_owned()
}

fn display_path(project: &Path, path: &Path) -> String {
    path.strip_prefix(project)
        .unwrap_or(path)
        .to_string_lossy()
        .into_owned()
}

#[cfg(test)]
mod tests {
    use super::{
        DevelopmentVersion, GradleFailure, RuntimeActivationFailure, envelope,
        with_development_context,
    };
    use anyhow::Error as AnyError;
    use std::path::Path;

    #[test]
    fn classifies_java_compiler_diagnostics() {
        let output = "/tmp/plugin/plugin-app/src/main/java/NotesActivity.java:86: error: cannot find symbol\n  symbol:   variable missingMessage\n/tmp/plugin/plugin-app/src/main/java/NotesActivity.java:86: error: cannot find symbol\n  symbol:   variable missingMessage\n";
        let failure = GradleFailure::classify(
            output,
            Path::new("/tmp/plugin"),
            Path::new("/tmp/plugin/build/logs/last-build.log"),
        );
        assert_eq!(failure.code, "JAVA_COMPILE_ERROR");
        assert_eq!(failure.phase, "compileJava");
        assert_eq!(failure.diagnostics[0].line, Some(86));
        assert_eq!(failure.diagnostics.len(), 1);
        assert!(failure.diagnostics[0].message.contains("missingMessage"));
        assert_eq!(failure.log_path, "build/logs/last-build.log");
    }

    #[test]
    fn does_not_label_generic_gradle_failures_as_missing_dependencies() {
        let failure = GradleFailure::classify(
            "Execution failed for task ':plugin-app:compileDebugJavaWithJavac'.",
            Path::new("/tmp/plugin"),
            Path::new("/tmp/plugin/build/logs/last-build.log"),
        );
        assert_eq!(failure.code, "GRADLE_ERROR");
    }

    #[test]
    fn classifies_android_library_plugin_resolution_with_an_actionable_fix() {
        let output = "Build file '/tmp/plugin/core-logic/build.gradle' line: 2\nPlugin [id: 'com.android.library'] was not found in any of the following sources:\n- Plugin Repositories (plugin dependency must include a version number for this source)\n";
        let failure = GradleFailure::classify(
            output,
            Path::new("/tmp/plugin"),
            Path::new("/tmp/plugin/build/logs/last-build.log"),
        );
        assert_eq!(failure.code, "ANDROID_LIBRARY_PLUGIN_UNDECLARED");
        assert_eq!(failure.phase, "resolvePlugins");
        assert_eq!(
            failure.diagnostics[0].file.as_deref(),
            Some("core-logic/build.gradle")
        );
        assert_eq!(failure.diagnostics[0].line, Some(2));
        assert!(failure.diagnostics[0].message.contains("shadow-plugin dev"));
    }

    #[test]
    fn development_failures_include_current_health_and_resume_context() {
        let error = AnyError::new(GradleFailure {
            phase: "compileJava".to_owned(),
            code: "JAVA_COMPILE_ERROR".to_owned(),
            message: "Java compilation failed".to_owned(),
            diagnostics: Vec::new(),
            log_path: "build/logs/last-build.log".to_owned(),
        });
        let error = with_development_context(
            error,
            Some("com.termux.shadow.notes".to_owned()),
            "/home/termux-shadow-notes".to_owned(),
            Some(DevelopmentVersion {
                version_code: Some(7),
                version_name: Some("1.6.0".to_owned()),
                generation: "7-abcdef".to_owned(),
                sha256: "a".repeat(64),
            }),
            Some(8),
        );
        let serialized = serde_json::to_value(envelope(&error, "dev")).unwrap();
        assert_eq!(serialized["code"], "JAVA_COMPILE_ERROR");
        assert_eq!(serialized["stateChanged"], false);
        assert_eq!(serialized["context"]["currentHealthy"]["versionCode"], 7);
        assert_eq!(serialized["context"]["nextVersionCode"], 8);
        assert_eq!(serialized["context"]["activeChanged"], false);
        assert_eq!(serialized["context"]["resumeCommand"], "shadow-plugin dev");
        assert_eq!(serialized["context"]["nextAction"], "FIX_AND_RERUN_DEV");
    }

    #[test]
    fn retryable_development_failures_recommend_the_same_dev_state_machine() {
        let error = AnyError::new(RuntimeActivationFailure {
            action: "run",
            plugin_id: "com.termux.shadow.notes".to_owned(),
            status: "WAITING_FOR_REPORT".to_owned(),
            generation: Some("8-candidate".to_owned()),
            operation_id: None,
            message: "runtime health confirmation timed out".to_owned(),
            diagnostics: Vec::new(),
            log_path: None,
            state_changed: true,
        });
        let error = with_development_context(
            error,
            Some("com.termux.shadow.notes".to_owned()),
            "/home/termux-shadow-notes".to_owned(),
            None,
            Some(9),
        );
        let serialized = serde_json::to_value(envelope(&error, "dev")).unwrap();
        assert_eq!(serialized["retryable"], true);
        assert_eq!(serialized["context"]["nextAction"], "RETRY_DEV");
        assert_eq!(serialized["context"]["resumeCommand"], "shadow-plugin dev");
    }

    #[test]
    fn runtime_failure_has_stable_activation_envelope() {
        let error = AnyError::new(RuntimeActivationFailure {
            action: "run",
            plugin_id: "com.termux.shadow.notes".to_owned(),
            status: "FAILED".to_owned(),
            generation: Some("13-deadbeef".to_owned()),
            operation_id: Some("operation-1".to_owned()),
            message: "plugin process died before stability".to_owned(),
            diagnostics: vec![super::Diagnostic {
                kind: Some("RUNTIME_CRASH".to_owned()),
                file: None,
                line: None,
                column: None,
                activity: Some("com.termux.shadow.notes.NotesActivity".to_owned()),
                error_type: Some("java.lang.IllegalStateException".to_owned()),
                message: "java.lang.IllegalStateException: boom".to_owned(),
            }],
            log_path: Some("crash/launch-1.json".to_owned()),
            state_changed: true,
        });
        let envelope = envelope(&error, "run");
        assert_eq!(envelope.code, "ACTIVATION_FAILED");
        assert_eq!(envelope.phase, "runtimeHealth");
        assert_eq!(envelope.state_changed, Some(true));
        assert_eq!(envelope.operation_id.as_deref(), Some("operation-1"));
        let serialized = serde_json::to_value(&envelope).unwrap();
        assert_eq!(serialized["hostOperationId"], "operation-1");
        assert!(serialized.get("operationId").is_none());
        assert_eq!(
            envelope.diagnostics[0].kind.as_deref(),
            Some("RUNTIME_CRASH")
        );
        assert_eq!(envelope.log_path.as_deref(), Some("crash/launch-1.json"));
    }

    #[test]
    fn unavailable_rollback_is_a_stable_non_mutating_admission_error() {
        let error = anyhow::anyhow!(
            "Host rejected control method rollback: Plugin has no retained rollback generation: test"
        );
        let envelope = envelope(&error, "rollback");
        assert_eq!(envelope.code, "ROLLBACK_NOT_AVAILABLE");
        assert_eq!(envelope.phase, "runtimeAdmission");
        assert_eq!(envelope.state_changed, Some(false));
        assert!(!envelope.retryable);
    }
}
