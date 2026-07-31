# Native `shadow-plugin` CLI

`shadow-plugin` is the native development and operator entry for the managed Termux + Tencent
Shadow chain. Version `0.12.0` is designed for short-lived Codex commands: the command process is a
small RPC client, while a same-UID Android service supervises a persistent native Worker and its
Gradle Daemon.

## Build this CLI from source

This repository contains the complete Rust native CLI. The Android Host application and the
canonical plugin template are separate platform inputs; they are not required to compile the CLI.

```sh
git clone https://github.com/ztm983620369/termux-shadow-cli.git
cd termux-shadow-cli
cargo test --locked
cargo build --locked --release
./target/release/shadow-plugin --version
```

For a Termux/aarch64 binary:

```sh
./scripts/build-android.sh
```

The script writes the generated ARM64 binary to `dist/shadow-plugin`; generated `target/` and
`dist/` contents are intentionally excluded from the source repository.

The recommended loop is one command:

```sh
cd ~/termux-shadow-notes
shadow-plugin dev
```

It discovers the project, runs fast diagnostics, fingerprints every module, reuses or builds one
artifact, commits the next version only after a successful build, publishes by exact SHA, waits for
real runtime health, and prints a compact result with the current context. Repeating the same command
after an unchanged edit returns `NO_CHANGES` without Gradle, publication, or launch.

The equivalent general compact JSON form remains available:

```sh
shadow-plugin dev --agent
```

`--agent` implies JSON and returns the committed version/SHA, source fingerprint, per-stage status
and timing, diagnostic counts, runtime health, Worker reuse, and `evidenceId`. A failed compile keeps
the provisional build input only in evidence and does not put a version on operation history; the
same next version is reused after the source is fixed. `dev --json` automatically uses this agent
contract, so agents do not need to remember a second output flag. A build or doctor cache entry is
reusable work, not a release reservation: automatic allocation advances only after publication
crosses the committed registry/receipt/history boundary.

## Installed layout

```text
$PREFIX/bin/shadow-plugin
$PREFIX/share/termux-shadow-plugin/template/
~/android-minimal-basic-portable/
~/.termux-shadow/
~/.termux-shadow/gradle-cache/
```

The Termux APK now embeds the ARM64 native binary and canonical template. Once the bootstrap exists,
the app validates asset fingerprints and atomically upgrades only the two system-tooling paths above;
it never overwrites `~/termux-shadow-*` user projects. Workstation `scripts/install-adb.sh` remains a
bootstrap/debug helper, not a phone-side dependency.

The CLI running inside Termux never invokes `adb`, starts an adb server, or adds `platform-tools` to
its build PATH. It uses local files, the project Gradle wrapper, and the fixed non-exported same-UID
Host control receiver.

## Worker model

```text
short-lived shadow-plugin client
        │ private Unix socket + JSON RPC
        ▼
shadow-plugin __worker
        │ parented by TermuxService
        ▼
Gradle Daemon
```

The socket directory is `0700`, the socket/state/request records are `0600`, and `SO_PEERCRED`
requires the same UID. Requests contain a typed action and argument vector, never an arbitrary shell
string. Project realpaths must stay under Termux home. An explicit `requestId` is idempotent; replay
returns the saved response and changed input under the same ID returns
`WORKER_REQUEST_ID_CONFLICT`.

`TermuxService` starts, adopts, monitors, and stops the Worker. The default idle timeout is 60
minutes. A busy build is serialized, while `info` returns persisted `BUSY` state within about 250 ms
with `currentRequestId`, `currentOperationId`, and `currentAction` instead of waiting behind Gradle.
Only a Daemon started by the current Worker is terminated by `shadow-plugin stop`.
The compatibility handshake checks protocol, CLI version, and binary SHA-256, so an APK tooling
upgrade cannot leave a same-version stale Worker executing an older inode. A successful stop reports
`STOPPING`; subsequent `info` reports `STOPPED` after the Supervisor has removed the process/socket.

If the Supervisor is unavailable, normal mode uses a correctness-preserving direct fallback and
states `executionMode: DIRECT_FALLBACK`. Set `TERMUX_SHADOW_WORKER_REQUIRED=1` when fallback must be
forbidden.

## Commands

```text
new [--dry-run]             atomically create a project and return its coding handoff
dev [--watch --diff]        recommended source-to-healthy loop; optional continuous controller
dev --workspace             run every configured project through the serialized Worker
retry / resume              aliases for dev; continue from the last safe stage
deploy                      advanced compatible stage/version control
deps resolve|vendor|status  resolve/lock, snapshot, and inspect dependency layers
deps audit [--workspace]    verify locks and exact cached artifacts without network access
deps import-gradle-cache    seed the managed cache from an existing Gradle home
import-android              transactionally migrate an Android application module
doctor [--full|--workspace] fast config gate; --full may build/validate
build [--fresh]             validate locally; native cache skips Gradle
publish / upgrade           native atomic publish and exact registration wait
run [--force] / rollback    correlated real-health activation
test-ui                     launch plus declarative first-frame UI smoke validation
status [--all|--compact]    explicit project/all scope plus lifecycle state
status --history            explicitly include retained generation history
context [--resume]          composite-cursor agent context; includes local source changes
evidence <evidenceId>       summary, diagnostics, tail, or complete redacted evidence
info                        resolved project plus live Worker/Daemon state
stop                        stop Worker and only its managed Gradle Daemon
sync [--dry-run]            update tooling; preserve identity, business source, and dependencies
```

Project resolution is reported by `info`: explicit option/environment or the nearest ancestor
containing `shadow-plugin.properties`. There is intentionally no writable home-directory fallback;
commands that need a project fail with `PROJECT_REQUIRED` instead of mutating the wrong workspace.
`new --dry-run --verbose` is the deterministic, non-interactive review surface: it prints the
template, target, complete derived identity, artifact names, default version, Host compatibility,
and publication intent without writing. This keeps scaffolding atomic and automation-safe while
making every inferred value inspectable.
After a real creation, `new` returns handoff contract v1: truthful source/registration/runtime
states, the complete bounded source tree, edit ownership, full
identity/Activity/dependency/view-ID/theme/smoke sources, and one shell-safe `nextCommand`.
Generated `.gradle`, `build`, `dist`, and
`local.properties` data is excluded. `--publish` reports `REGISTERED` only after the exact package
SHA appears in the Host registry; runtime remains `UNPROVEN` until `dev` or `run` completes the
first-frame and stability protocol. JSON and agent output remain one document.
`status` is read-only and deliberately differs: without project context it falls back to all
registered plugins, labels that fallback in human output, and emits `scope`, `filterActive`,
`filtered`, `totalPlugins`, `matchedPlugins`, and `fallbackToAll` in JSON. An empty registry is
reported as `none registered`; a project filter that excludes existing records is reported as
`none matched` with a `status --all` hint.

## Workspace defaults and batch operations

Place `.shadow-workspace.toml` at the common ancestor of the projects. CLI options and their
environment variables take precedence, workspace defaults come next, and built-in defaults remain
last. Project paths must be relative, stay inside the workspace after canonicalization, be unique,
and already contain `shadow-plugin.properties`; unknown keys and misspellings fail closed.

```toml
schema-version = 1

[defaults]
dependency-policy = "cache-first"
toolchain = "./toolchain"
template = "./template"
output = "agent" # human | json | agent

[projects]
notes = "plugins/notes"
calculator = "plugins/calculator"
```

Use `--project @notes` for an unambiguous named project. `doctor --workspace`, `dev --workspace`,
and `deps audit --workspace` produce one aggregate JSON document when JSON/agent output is selected.
Pass `--human` to override a workspace `output = "json"` or `output = "agent"` default.
The batch execution policy is intentionally `SERIALIZED_WORKER`: it preserves dependency-cache,
version-allocation, publication, and evidence transactions instead of claiming unsafe parallel
Gradle publication.

`dev --watch` is a foreground human event stream. It performs one initial resumable `dev`, watches
exactly the project inputs covered by the native source fingerprint, debounces filesystem bursts,
and continues after a failed compilation so the next edit can recover. `--diff` prints bounded
added/modified/deleted input paths. Each trigger starts a separate CLI request, so the persistent
Worker remains available to `info`, `stop`, and other sessions. Because the stable `--json` contract
is exactly one document, `--watch` rejects `--json`/`--agent`; automation should issue ordinary
idempotent `dev --agent` requests.

`context --resume` persists a private per-project composite cursor below
`~/.termux-shadow/sessions/context/`, not in the source tree. Protocol v2 returns `nextRevision` and
`nextCursor`; the cursor covers registry state, project content, toolchain-aware source state,
publication/history, and stable Worker state. `--since-cursor` is the stateless equivalent.
`--since-revision` remains a Host-only compatibility check and intentionally does not claim to
detect local edits.

`sync` upgrades schema-1 properties in place, replaces only the managed module build script, saves
its previous text as `plugin-app/build.gradle.pre-shadow-sync`, and preserves detected dependency
declarations in `plugin-app/dependencies.gradle`. Java/Kotlin/resources below `plugin-app/src/` are
never overwritten, and the project `.gitignore` remains project-owned. An interrupted write leaves
`.shadow-tooling-sync-incomplete.json`; doctor blocks builds until an idempotent `shadow-plugin sync`
finishes the transaction.

`dev` includes that resolution, current healthy version, `nextVersionCode`, stage result, runtime
health, next action, and `evidenceId` in its normal result. Use `--no-run` only for an intentional
build/register-only operation. Fast doctor is always part of `dev`; the Gradle-backed package check
remains explicit as `doctor --full`.

## Build, cache, and publication

Dependency resolution is independent from local publication. The default `cache-first` mode uses
the selected cache and does not contact repositories unless `--allow-network` is explicit. Use
`--online` for normal repository resolution and `--offline` for a hard cache-only gate. The managed
layers are the portable base cache, writable `~/.termux-shadow/gradle-cache`, then an optional
project `vendor/gradle-home`. Useful commands are:

```sh
shadow-plugin deps status
shadow-plugin deps import-gradle-cache --from ~/.gradle
shadow-plugin deps resolve --allow-network --lock
shadow-plugin deps vendor
shadow-plugin dev --offline
```

`shadow-dependencies.lock.json`, Gradle lockfiles, and the project-owned
`plugin-app/dependencies.gradle` declarations are fingerprinted inputs. Development may
resolve and update them with explicit network approval; `publish`, `upgrade`, and the publish stage
of `dev` require a valid lock, re-resolve and SHA-256-compare the complete artifact set, and execute
the package build offline. Thus repository availability or changed cache content cannot silently
alter a release while ordinary development is no longer trapped in an isolated portable cache.

The input fingerprint is module-name independent. It covers every Gradle module's conventional
`src` and `libs` trees (including nested modules), build scripts/build logic, resources/manifest,
plugin config, Gradle and Shadow inputs, requested version, signing identity, CLI/template protocol,
and toolchain markers. Generated `build`, `.gradle`, `.cxx`, and `dist` trees are excluded at every
module depth. A cache hit is accepted only after the artifact still exists and its length, SHA-256,
package identity, resource ID, host range, and validation record agree.

`dev --fresh` and `deploy --fresh` always bypass both the native artifact cache and the registered-source
`NO_CHANGES` fast path. Standalone `build` and `doctor --full` never consume `nextVersionCode`; a
first deploy after doctor still uses the project's default code. A newly scaffolded, unregistered
project is a valid empty runtime-artifact state and can use `deploy` immediately.

Fast doctor also checks multi-module Android Library declarations before Gradle starts. A module that
uses `com.android.library` without a centrally versioned root declaration receives
`ANDROID_LIBRARY_PLUGIN_UNDECLARED` and the exact root-plugin fix. Gradle resolution distinguishes an
undeclared plugin from `ANDROID_LIBRARY_PLUGIN_NOT_IN_CACHE` or
`ANDROID_LIBRARY_PLUGIN_RESOLUTION_FAILED` instead of reporting a generic dependency failure.
Dependency errors point directly to `deps status`, online lock resolution, cache import, and vendor
snapshot commands. Invalid slugs, including digit-first names, fail before template access and
return a valid suggested `shadow-plugin new ...` command.

Rust is the sole inbox publisher. A cold publish asks Gradle only for
`copyShadowPluginDebugToDist`; the native publisher re-hashes the validated artifact, writes a private
`.part`, fsyncs it, atomically renames it into `~/.termux-shadow/inbox`, and atomically commits both
receipts. `publishShadowPluginDebug` is retired. Consequently:

- a preceding `build` is consumed without Gradle;
- an exact registered SHA returns `ALREADY_PUBLISHED`;
- mature-project bare `publish` returns `VERSION_REQUIRED`;
- lower versions return `DOWNGRADE_BLOCKED` unless explicitly allowed;
- equal version/different SHA returns `VERSION_NOT_INCREASING`.

Gradle configuration cache is compatible with the canonical packaging tasks, but is not enabled by
default. Version properties form a new cache key on every automatic bump; local measurements showed
that a cache miss was slower than the warm-Daemon path. Set
`TERMUX_SHADOW_CONFIGURATION_CACHE=1` for same-version experiments. The default optimized path is the
persistent Worker + Gradle Daemon + native content cache.

## Stable JSON and evidence

Every `--json` success or failure emits exactly one camelCase document. `dev --json` is intentionally
the same compact contract as `dev --agent`. Failures include stable
`phase`, `code`, `retryable`, `stateChanged`, bounded diagnostics, and an evidence reference. Java,
Kotlin, Android resources, manifest merge, DEX, dependency-cache, package, version, publication,
registration, Worker, and activation errors are distinguished.

Development failures additionally include project, current proven healthy version, unchanged active
status, unchanged `nextVersionCode` where applicable, a next-action enum, and
`resumeCommand: "shadow-plugin dev"`. Human mode prints the same decision context before the evidence
reference, so neither a follow-up `status` nor a manual evidence query is required for routine fixes.

Public output has four deliberate levels:

```text
--agent                   compact Codex decision contract; implies JSON
default --json            one-line general decision summary; NO_CHANGES omits inactive stages
--verbose --json          detailed current operation and Worker/proof metadata, not generation history
evidence <id> --full      complete redacted stdout/stderr, diagnostics, snapshots and manifests
```

Default Worker-backed results expose `workerPid`, `workerReused`, and `evidenceId`. Verbose results
use `workerRequestId`, `workerOperationId`, and `hostOperationId` for the three distinct correlation
domains. They do not repeat plugin identity inside the launch proof. `status --json` defaults to the
current project's compact view, even with `--verbose`; use `--history`, `--all`, or `--raw` only when
retained generation history or raw reports are actually required.

Lossless evidence is stored privately at:

```text
~/.termux-shadow/evidence/<evidenceId>/
  request.json result.json stdout.log stderr.log diagnostics.json timing.json
  state-before.json state-after.json artifact-manifest.json
  gradle-*.log redaction.json evidence-manifest.json
```

The manifest records SHA-256 and byte length for each captured file; `complete=true` means logs were
not truncated. Secret-shaped values are redacted. Successful evidence is bounded to the latest 100
operations; failures are retained.

## Runtime health invariant

Registration creates `candidateGeneration`. Launch creates a separate `activatingGeneration`; the
old `activeGeneration` remains unchanged until the business Activity completes create/resume, draws
a first frame, and its process survives the stability window. Only then does the Host atomically
promote the generation to active. Crash, process death, or timeout invalidates stale health proof,
increments failure accounting, and cannot replace the prior healthy active. Registry schema 3 safely
migrates the former schema 2 in-progress pointer representation.

`shadow-plugin test-ui [pluginId]` (or `run --smoke`) reads a bounded schema-1
`shadow-smoke.json`. After the first frame, the plugin process executes only declarative actions
(`assertDisplayed`, `assertText`, `click`, `focus`, `input`, `scroll`, `assertImeActive`, and bounded
`wait`). The schema is closed, every interactive action requires a view, and cumulative declared
wait time is capped at 10 seconds. A failed step is a runtime-health failure and follows the same
proof-only rollback path; successful proof records the step count and duration in launch and
registry reports.

`import-android SOURCE --slug NAME` creates an isolated scaffold in a hidden staging directory,
copies Java sources, resources, and dependencies, rewrites the namespace and launcher to
`ShadowActivity`, and commits the target only after migration succeeds. It writes
`shadow-import-report.json` for AppCompat, Application, Service, Provider, Receiver, and dependency
compatibility decisions; `--dry-run` performs analysis without writing. Kotlin is detected during
analysis and reported as a blocking compatibility item instead of producing a project that fails
later during compilation. Project/file dependencies, interpolated or otherwise dynamic coordinates,
non-`implementation` configurations, annotation-processor declarations, and variant-specific
dependency declarations are also blocking: the report identifies the source line and no target is
written until the dependency graph can be migrated losslessly. Auto-derived namespaces escape Java
reserved words, and transplanted Activity callbacks are widened only where the Shadow public ABI
requires it. Custom Application classes, multiple Activity declarations, and Service/Receiver/
Provider components are blocking compatibility items instead of being silently dropped from the
generated Shadow manifest.

## Build locally

```sh
./scripts/build-android.sh
```

The output is `dist/shadow-plugin`, an ARM64 PIE for Android API 23 using
`/system/bin/linker64`.
