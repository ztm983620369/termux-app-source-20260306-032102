# Termux Shadow Platform

The host treats Shadow as a managed platform rather than a fixed plugin zip. The complete managed
state is rooted at `/data/data/com.termux/files/home/.termux-shadow` so packages, runtime state,
engine cache, policy, telemetry, and operator reports have one ownership boundary.

## Storage layout

```text
~/.termux-shadow/
  inbox/                    Atomic deployment input
    archive/                Successfully consumed packages
  repository/plugins/       Immutable content-addressed source of truth
  runtime/
    state/                  Atomic registry, migration markers, launch context
    journal/                Fsync-backed operation journal
    packages/               Verified launch snapshots
    managers/               Content-addressed manager APKs
    staging/                Transaction staging
    locks/                  Reserved coordination boundary
  engine/
    cache/                  Shadow unpack, dex, and native-library cache
    state/                  Shadow installed-plugin database
    manager-odex/           Dynamic manager optimized code
  quarantine/               Rejected packages plus failure metadata
  logs/{host,plugins,audit}/ Structured rotating JSONL logs
  crash/                    Java crash reports and system exit traces
  reports/                  Registry, health, launch, migration, and exit reports
  worker/                   Same-UID native Worker socket, state, lock, and replay cache
  evidence/                 Hashed, complete secret-redacted CLI operation evidence
  history/                  Bounded per-plugin development history
  config/                   Trust keys and runtime policy
  exports/                  Operator export boundary
```

## Single package ingress

The only deployment input is `~/.termux-shadow/inbox/*.shadowpkg`. The host does not scan fixed
package paths, accept `.zip` aliases, or import schema 1 packages. Unsupported complete inbox files
are moved to quarantine with explicit failure metadata.

Schema 2 `.shadowpkg` packages contain `config.json`, `termux-shadow.json`,
`checksums.sha256`, loader/runtime/business APKs, and an optional `signature.json`. The host checks
zip safety and size bounds, metadata/config agreement, host compatibility, Shadow MD5 fields,
SHA-256 coverage, resource package identity, and the configured RSA trust policy before install.

The generation id is derived from version code and bundle SHA-256. Re-importing identical content
is a no-op. Inbox packages are archived after success and moved to quarantine after rejection.

## Lifecycle

Every generation follows explicit transitions from discovery through verification, installation,
activation, health, rollback, disable, and removal. Registry commits are atomic and every operation
is appended to the durable journal. Interrupted activation rolls back on recovery, interrupted
removal resumes, and a missing runtime/repository copy is repaired from its verified peer.

Each launch has a unique operation token and a configurable health timeout. Registry schema 3 keeps
`activatingGeneration` separate from `activeGeneration`: submitting a candidate launch cannot
replace the proven active version. `HEALTHY` is committed only after Activity create/resume, first
frame, and process-stability proof. Stale callbacks are ignored. Candidate failures preserve the
old active version; repeated failures of an established generation trip the configured threshold
and can select only a retained generation with persisted runtime-health proof. Storage recovery also
checks that fallback package bytes still match their registered SHA-256.

## Policy

`config/policy.json` schema 3 declares the immutable `SHADOWPKG_INBOX_ONLY` ingress mode and controls
signature requirements, bundle size, retained versions, launch timeout, stability window, and
consecutive-failure threshold. Release builds require a trusted signature by default.
`config/trusted-keys.json` contains X.509 RSA public keys identified by key id.

The host publishes `reports/health.json`, `reports/registry.json`, and isolated
`reports/launch/<pluginId>.json` records (`last-launch.json` is compatibility-only) for inspection
without reading transactional state directly. Android 11+ historical exits capture ANR,
Java/native crash, low-memory, and signal metadata under `reports/process-exits`.

## Native development client

The phone-side entry is `$PREFIX/bin/shadow-plugin`. Expensive requests use a private Unix-socket
Worker supervised by `TermuxService`, so separate short Codex commands reuse Gradle and validated
artifacts. Rust is the only production inbox publisher; Gradle only builds and validates `dist`.
The normal edit loop is:

```sh
shadow-plugin dev --agent
```

The CLI never invokes adb. The Termux APK embeds the ARM64 client and canonical template, verifies
their fingerprints, and atomically upgrades only the system tooling paths after bootstrap; existing
user plugin projects are never overwritten.
