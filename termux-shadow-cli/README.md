# Native `shadow-plugin` CLI

`shadow-plugin` is the native development and operator entry for the managed Termux + Tencent
Shadow chain. Version `0.8.0` is designed for short-lived Codex commands: the command process is a
small RPC client, while a same-UID Android service supervises a persistent native Worker and its
Gradle Daemon.

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
new                         create a standard isolated project
dev                         recommended source-to-healthy loop; runtime proof is the default
retry / resume              aliases for dev; continue from the last safe stage
deploy                      advanced compatible stage/version control
doctor [--full]             fast config gate; --full may build/validate
build [--fresh]             validate locally; native cache skips Gradle
publish / upgrade           native atomic publish and exact registration wait
run [--force] / rollback    correlated real-health activation
status [--compact]          active/candidate/activating/previous healthy state
status --history            explicitly include retained generation history
context [--since-revision]  compact agent context capsule or revision delta
evidence <evidenceId>       summary, diagnostics, tail, or complete redacted evidence
info                        resolved project plus live Worker/Daemon state
stop                        stop Worker and only its managed Gradle Daemon
sync [--dry-run]            update tooling while preserving config and plugin-app/
```

Project resolution is reported by `info`: explicit option/environment, nearest ancestor containing
`shadow-plugin.properties`, then `~/termux-shadow-basic-plugin`.

`dev` includes that resolution, current healthy version, `nextVersionCode`, stage result, runtime
health, next action, and `evidenceId` in its normal result. Use `--no-run` only for an intentional
build/register-only operation. Fast doctor is always part of `dev`; the Gradle-backed package check
remains explicit as `doctor --full`.

## Build, cache, and publication

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
undeclared plugin from `ANDROID_LIBRARY_PLUGIN_NOT_IN_OFFLINE_CACHE` instead of reporting a generic
dependency failure. Invalid slugs, including digit-first names, fail before template access and
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

## Build locally

```sh
./scripts/build-android.sh
```

The output is `dist/shadow-plugin`, an ARM64 PIE for Android API 23 using
`/system/bin/linker64`.
