# session-sync-core

Purpose: keep terminal session state and file-context mapping in one root module,
so core logic is not scattered across `app` and `file-manager`.

## Current Components

- `SessionRegistry`: central publish/subscribe registry with in-memory and SharedPreferences persistence.
- `SessionEntry` / `SessionSnapshot`: shared cross-module session models.
- `FileRootResolver`: maps a session to a file root (local root or reserved SFTP mount root).
- `SftpMountManager`: sshfs mount lifecycle (for devices with FUSE support).
- `SftpProtocolManager`: FUSE-free SFTP engine (directory listing + file materialize).
- `SessionFileCoordinator`: single entry for file-page session selection, mount/protocol fallback, and virtual path operations.
- `SessionSyncTracer`: centralized diagnostics trace sink (`.termux/session-sync/trace.log`), used by registry/coordinator.

## Extension Points

- Add `SftpMountOrchestrator` for sshfs lifecycle, reconnect, and health checks.
- Add `SessionPolicyEngine` for dedupe, conflict, and recovery policy.
- Keep `app` and `file-manager` as thin adapters only.

## Core Rules

- UI modules should not call `SftpMountManager` or `SftpProtocolManager` directly.
- UI modules should call `SessionFileCoordinator` only.
- Every state transition that can affect cross-module behavior should emit a trace event via `SessionSyncTracer`.
