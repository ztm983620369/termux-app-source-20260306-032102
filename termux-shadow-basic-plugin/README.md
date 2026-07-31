# Termux Shadow Plugin SDK

This is the canonical schema 2 Shadow plugin workspace for Termux. Plugin identity lives only in
`shadow-plugin.properties`; Gradle, Android resources, the generated Shadow manifest, packaging, and
the CLI all consume that file.

## Native fast path

The Termux APK embeds and atomically upgrades the Rust `shadow-plugin` binary in `$PREFIX/bin` and
this canonical template in `$PREFIX/share/termux-shadow-plugin/template`. It can be called from any
directory, finds the nearest plugin project automatically, and discovers the self-contained
`~/android-minimal-basic-portable` toolchain without network downloads. APK upgrades do not overwrite
existing `~/termux-shadow-*` projects.

The entire phone-side workflow is local-only: the CLI never calls adb. Publishing is an atomic local
inbox transaction and lifecycle control uses the Host's non-exported same-UID receiver.

Inside Termux:

```sh
shadow-plugin info
shadow-plugin new notes "Notes"
cd ~/termux-shadow-notes
shadow-plugin dev
```

Create, validate, build, publish, and confirm registration of another logical plugin with one
command:

```sh
shadow-plugin new notes "Notes" --publish
```

The generated project is `~/termux-shadow-notes` by default. It receives a unique `pluginId`,
`partKey`, Java namespace, Activity, APK/bundle names, and the first free resource package ID.

## CLI

```text
shadow-plugin new <slug> [display-name] [options]
shadow-plugin doctor [--project-only] [--full] [--fresh] [--failures-only]
shadow-plugin build [--version-code N] [--version-name NAME] [--fresh]
shadow-plugin publish [--version-code N] [--version-name NAME] [--no-wait] [--allow-downgrade]
shadow-plugin upgrade <version-code> <version-name>
shadow-plugin dev [--no-run] [--version-name NAME] [--fresh] [--watch [--diff]] [--agent|--json]
shadow-plugin dev --workspace
shadow-plugin retry|resume [dev options]
shadow-plugin deploy [--bump patch|minor|major] [--run] [--fresh] [--json]
shadow-plugin deps resolve [--allow-network] [--lock] [--refresh]
shadow-plugin deps import-gradle-cache [--from PATH]
shadow-plugin deps vendor|status|clean
shadow-plugin deps audit [--workspace]
shadow-plugin import-android <source> --slug <slug> [--dry-run]
shadow-plugin status [--all] [--compact] [--wait] [--timeout SECONDS] [--json]
shadow-plugin context [--resume|--since-cursor CURSOR|--since-revision N] --json
shadow-plugin evidence <evidenceId> [--diagnostics|--tail N|--full]
shadow-plugin run|rollback [pluginId]
shadow-plugin test-ui [pluginId] [--smoke-file PATH]
shadow-plugin disable|enable [pluginId]
shadow-plugin delete [pluginId] --yes
shadow-plugin refresh
shadow-plugin config
shadow-plugin clean
shadow-plugin stop
shadow-plugin sync [--dry-run]
```

Command behavior:

- `new` copies only source/tooling inputs, rewrites the business source package and Activity class,
  rejects identity/resource collisions, and runs `doctor` before committing the new directory. Its
  final handoff contains the complete bounded source tree, edit ownership, full identity and key
  sources, a
  shell-safe next command, and separate source/registration/runtime states. `--publish` says
  `REGISTERED` only after exact-SHA confirmation; it never implies runtime health.
- `doctor` checks config, source/manifest alignment, SDK assets, Java, Android SDK, aapt2, legacy
  artifacts, live registry resource ownership, sibling project collisions, and signing inputs. Full
  output shows plugin diagnostics before package validation and reports build/tool warnings separately.
- `build` produces and validates `.shadowpkg` without publishing. Hash-identical inputs return the
  validated artifact from the native cache without starting Gradle; `--fresh` forces Gradle.
- `publish` rejects unsafe versions before Gradle, reuses a preceding validated `build` without any
  Gradle invocation, returns `ALREADY_PUBLISHED` for an exact registered SHA, and otherwise performs
  native atomic inbox publication plus SHA-based registration confirmation. Bare mature-project
  publish requires a version; downgrade is an explicit operator-only exception.
- `dev` is the recommended edit loop. It discovers the project, runs fast doctor checks, computes
  source/toolchain fingerprints across all modules, reuses or builds the artifact, commits the next
  version after build success, publishes, launches, waits for runtime proof, and records bounded
  history. `retry` and `resume` are aliases for the same idempotent state machine; `--no-run` is an
  explicit advanced escape hatch.
- `dev --json` automatically emits the stable agent contract. It includes project resolution,
  current context and the next action, so routine success needs no `status` and routine failure needs
  no evidence lookup. `--agent` remains the explicit spelling for coding-agent callers.
- Fast doctor rejects an unversioned `com.android.library` module declaration before Gradle and tells
  the user exactly what to add to the root plugins block. Missing offline plugin artifacts have a
  separate error code. Digit-first or otherwise invalid slugs fail before template access with a
  valid suggested slug.
- Default `--json` is a one-line decision capsule. An unchanged deploy returns only status, plugin,
  active generation, duration, Worker reuse and `evidenceId`; changed deploys add version/SHA and
  build/publish/run results. Add `--verbose` for fingerprints, full Worker state, stage timing,
  `workerRequestId`, `workerOperationId`, `hostOperationId`, and history path.
- Build orchestration fingerprints identity/Gradle/Manifest inputs and invalidates stale outputs only
  when those inputs change; normal business-source iterations retain incremental build speed.
- `status` labels the project receipt as `last published` and derives `currently active` independently
  from the registry pointer. JSON defaults to the current project's compact view; `--verbose`,
  `--all`, and `--raw` explicitly request expanded data. `--compact` also exposes nextVersionCode,
  dirtySinceActive, cachedArtifact,
  activating generation, previousHealthy, last-published runtime proof, and the safe active artifact;
  `--all` lists every logical plugin; `--wait` verifies the exact receipt SHA. Normal status also
  repairs missing project-local healthy pointers from the hash-verified managed repository.
- `upgrade` is a concise publish command with an explicit monotonically increasing version.
- lifecycle commands use the Host's non-exported same-UID control receiver and serialized state
  machine; `HEALTHY` requires business Activity resume, first draw, and a process-stability window.
  Binder death wins before promotion, rollback targets only proof-bearing history, per-plugin launch
  reports prevent cross-plugin context overwrite, and concurrent launches return `LAUNCH_BUSY`.
- `--json` provides one stable camelCase JSON document on success and failure. Lossless, hashed,
  redacted operation evidence lives in `~/.termux-shadow/evidence/<evidenceId>/`; typed diagnostics
  cover Java, resources, manifest, dependencies, Kotlin, DEX, publication, Worker, and activation.
  A correlated Java runtime crash adds `runtime-crash.json` and `runtime-crash.log` with Activity,
  exception type, and bounded stack trace; default JSON keeps only the compact crash diagnostic.
- Native builds use a short-lived client and a TermuxService-supervised Worker over a private
  same-UID Unix socket. It reuses Gradle across independent commands; `info` reports real
  READY/BUSY/Daemon state and `stop` releases only its managed Daemon. Default idle is 60 minutes.
- `context` returns the compact fingerprint/version/pointer/recommended-action capsule and supports
  registry revision deltas; `evidence` retrieves details only when needed.
- `info` prints the current directory and whether the project came from an explicit override, nearest
  ancestor, or was not resolved. Commands never silently fall back to a standard home project.
- `sync` updates canonical tooling/runtime inputs and migrates schema-1 config keys without changing
  identity values. It replaces the managed module script only after saving
  `plugin-app/build.gradle.pre-shadow-sync`, extracts dependency declarations to
  `plugin-app/dependencies.gradle`, and never overwrites `plugin-app/src/` business source or the
  project `.gitignore`;
- leaves an explicit incomplete-transaction marker if the process is interrupted, so doctor blocks
  mixed-tooling builds until `shadow-plugin sync` is rerun.

## Single config

Edit `shadow-plugin.properties` rather than Gradle identity fields:

```properties
schemaVersion=2
pluginSlug=basic
projectName=TermuxShadowBasicPlugin
pluginId=com.termux.shadow.basic
partKey=termux-basic-plugin
namespace=com.termux.shadow.basic
activityClassName=com.termux.shadow.basic.TermuxShadowBasicActivity
resourcePackageId=0x7C
pluginApkName=termux-shadow-basic-plugin-debug.apk
bundleBaseName=termux-shadow-basic
displayName=Termux 基础插件
description=由 Termux Shadow 工业化插件 SDK 构建
defaultVersionCode=1
defaultVersionName=1.0.0
minHostVersionCode=118
maxHostVersionCode=999999
applicationClassName=
applicationTheme=android.R.style.Theme_Material_Light_NoActionBar
activityTheme=android.R.style.Theme_Material_Light_NoActionBar
screenOrientation=unspecified
softInputMode=adjustNothing
configChanges=orientation|screenSize|keyboardHidden
```

Keep `applicationId 'com.termux'`: logical identity comes from `pluginId` and `partKey`. Every
simultaneously loadable logical plugin must have a distinct resource ID in `0x02..0x7E`.
`shadow-plugin new` allocates downward from `0x7B`, checks sibling workspaces and the live registry,
and never reuses the standard plugin's reserved `0x7C`. The shell scaffolder remains only as the
project-shim fallback.

## Dependency policies

Local deployment and reproducible packaging no longer imply forced offline development. The CLI
defaults to `cache-first`: portable read-only cache → managed
`~/.termux-shadow/gradle-cache` → project `vendor/gradle-home`. Repository access is explicit:

```sh
shadow-plugin deps status
shadow-plugin deps import-gradle-cache --from ~/.gradle
shadow-plugin deps resolve --allow-network --lock
shadow-plugin deps vendor
shadow-plugin dev --offline
```

`online` resolves through configured repositories and warms the shared cache; `offline` never uses
the network. Publish/upgrade require a valid `shadow-dependencies.lock.json` plus Gradle lockfiles,
re-resolve and SHA-256-compare the complete artifact set, and build the release offline. Dependency
failure diagnostics include the exact recovery command.

## Migration and UI smoke validation

Use `shadow-plugin import-android SOURCE --slug NAME --dry-run` to get a compatibility report, then
rerun without `--dry-run` for an atomic staged import. The importer copies sources/resources and
dependency declarations, rewrites the launcher to `ShadowActivity`, and records AppCompat and
non-Activity component decisions in `shadow-import-report.json`. The current template is Java-only;
Kotlin sources are reported as a blocking migration item before any target is written. Project/file
dependencies, dynamic coordinates, non-`implementation` configurations, annotation processors, and
variant-specific dependency declarations are likewise reported with their source line and block the
transaction instead of silently generating an incomplete project. Java keywords in derived
namespaces and the public lifecycle visibility required by `ShadowActivity` are normalized
deterministically. Custom Application classes, multiple activities, and Service/Receiver/Provider
components remain explicit blocking items until their Shadow lifecycle mapping is implemented.

Create a bounded `shadow-smoke.json` and run `shadow-plugin test-ui`. Supported actions are
`assertDisplayed`, `assertText`, `click`, `focus`, `input`, `scroll`, `assertImeActive`, and `wait`.
Every interactive step names a view (plain ID, `id/name`, or `@id/name`), cumulative `waitMs` is
limited to 10 seconds, and unknown fields are rejected. Smoke execution starts only after first
draw; a failed step is an activation failure and cannot replace the previous healthy generation.

## Package and registration

The build creates:

```text
dist/<bundleBaseName>-<versionName>.shadowpkg
dist/last-published.json
```

Publication and runtime health are deliberately separate. A newly published artifact is immediately
marked `runtimeStatus=UNPROVEN` and receives a file-specific
`<artifact>.runtime.json` sidecar. Only first-frame plus process-stability proof creates or replaces:

```text
dist/active.shadowpkg        exact hash-verified copy of currently active healthy generation
dist/last-healthy.json       generation/version/SHA and runtime proof
dist/last-runtime.json       latest published artifact's runtime status and error
```

An activation failure changes the attempted artifact and `last-published.json` to
`ACTIVATION_FAILED`, but never replaces `active.shadowpkg` or `last-healthy.json`. Gradle preserves
the reserved active artifact while replacing ordinary build outputs, and the Rust publisher never
mistakes `active.shadowpkg` for a new candidate.

The Rust CLI is the only publisher. It re-hashes the validated `dist` artifact, writes a private
`.part`, fsyncs it, and atomically renames it into:

```text
~/.termux-shadow/inbox/<pluginId>-<versionName>-<sha256-prefix>.shadowpkg
```

The host independently verifies schema, metadata/config mapping, MD5, complete SHA-256 coverage,
optional RSA signature, host compatibility, and resource package ID. Successful imports become a
candidate and move to `inbox/archive`; failures move to `quarantine` without changing the active
generation.

No fixed ZIP path, adb publication recipe, direct registry edit, or second publisher is supported.
The former Gradle `publishShadowPluginDebug` task is retired and fails closed.

## Signing

For a signed release, provide both inputs before `publish`:

```sh
export TERMUX_SHADOW_SIGNING_KEY_PKCS8=/secure/path/private-key.pkcs8.pem
export TERMUX_SHADOW_SIGNING_KEY_ID=production-2026
shadow-plugin upgrade 2 2.0.0
```

Install only the matching X.509 RSA public key in
`~/.termux-shadow/config/trusted-keys.json`. Never store the private key in the project or managed
Shadow home.

## Layout

```text
shadow-plugin                 project shim to the native CLI (shell fallback remains available)
shadow-plugin.properties      only plugin identity/config source
build.gradle                  deterministic package/validator; publication task retired
scripts/
  new-plugin.sh               atomic project scaffolder
  doctor.sh                   fast/full diagnostics
  status.sh                   registration and platform status
  build-debug.sh              build/publish orchestration
  lib/common.sh               shared parsing/environment helpers
plugin-app/                   business APK source and dependencies
shadow/                       loader, runtime, and compile-only Shadow inputs
```
