# Terminal Rendering Phase 2

Date: 2026-07-25

## Scope

This phase addresses the bottlenecks measured by
`/root/termux-tui-lab/reports/industrial-industrial-20260725-final2/REPORT.md`.
The baseline was correct but slow under extreme load: steady-workload jank was
108/129 frames, command submission p95 was 205.31 ms, retained-row retirement
peaked at 1748 entries, and measured tab preparation was about 186 ms.

The implementation does not change terminal truth or hide work. libghostty-vt
remains the parser and render-state authority. PTY bytes are never dropped,
visible frames still require complete viewport coverage, and a failed native
render transaction still enters the explicit Java compatibility fallback.

## Implemented Changes

### ABI-2 row ingestion

`GhosttyRenderDelta.copyRowCells()` copies the six fixed cell fields for one
changed row into caller-owned reusable arrays. The row directory and payload
bounds are validated once per row instead of once for every field of every
cell. Text remains decoded from the same synchronous zero-copy native packet.

### Retained renderer lifecycle

`TerminalRenderer` now reconfigures font metrics in place. Live pinch reflow no
longer constructs and retires a complete renderer on every VSync. Session
changes reset model-specific retained state while preserving metric and
allocation caches.

`GhosttyRenderNodeRenderer` now uses bounded row, rectangle-command, and
text-command pools. Direct parent-Canvas commands own no RenderNode or GPU
resource, so replaced rows are recycled immediately on their UI-thread owner.
The old deferred retirement queue is retained only as a no-op compatibility
hook; `retiredPending` must remain zero.

Detached ViewPager pages retain their complete Java row cache. Reattachment
requests a full packet only if exact viewport coverage is missing. Permanently
removed tabs explicitly release their retained resources.

The bounded cache/pool lifecycle and VSync invalidation model were cross-checked
against Sora Editor's local `RenderNodeHolder`. Its nested RenderNode structure
was deliberately not copied: this terminal keeps direct parent-Canvas rows
because exact viewport coverage and the existing black-row regression gate are
the stronger correctness constraint. Ghostty remains the source of terminal
state and dirty-row semantics.

### Real-time publication

Healthy Ghostty parsing remains on the PTY reader thread. Native screen-update
notifications are now published to Android UI at most once per Choreographer
VSync. All bytes and native state revisions continue to advance immediately;
only redundant UI notifications inside the same display interval are merged.

`TerminalView` independently merges invalidation requests into one VSync
callback and always draws the newest native delta. Ghostty Android invalidation
is full-view because native row dirtiness is learned during packet decode, but
the native packet itself remains incremental.

### Multi-tab work ownership

`TerminalSessionSurfaceView` now has one identity-deduplicated render-work
entry point per VSync. The committed page and real transition target are foreground
owners. At most one inactive retained-state prewarm runs per frame, so many
concurrent TUI sessions cannot enqueue one UI task each. Inactive PTYs and
Ghostty parsers remain fully independent and continue running.

Background prewarm does not invalidate an invisible View hierarchy. Selecting
a tab promotes it out of the background queue, brings its retained state to the
latest complete revision, and preserves the existing complete-frame commit
gate and recovery checks.

## Diagnostic Contract

The following logs are deliberately retained for device validation:

- `TerminalSession frame-notify-v4`: native update requests, same-frame
  coalescing, publications, current/max VSync wait, and parser authority.
- `TerminalView frame-publication-v4`: View requests, callbacks, coalescing,
  actually presented/skipped frames, revisions, draw time, scheduling wait,
  and completeness. A skipped draw never advances the presented revision.
- `TerminalSessionSurface render-work-v4`: foreground/background work,
  coalescing, queue depth, background wait, and prewarm cost.
- `TermuxRenderV2`: native/delta counters plus row/command pool allocation and
  reuse, `estimatedBytes`, viewport reuse/retry counters, and render time.

The industrial instrumentation JSON also records `frame_notify_requests`,
`frame_notify_coalesced`, and `frame_notify_published` for every sampled
session. The lab analyzer and targeted logcat filter understand all phase-2
metrics.

## Local Verification

Completed without ADB:

```text
third_party/ghostty-vt/render-query status -> batch
git diff --check -> clean
:terminal-emulator:testDebugUnitTest -> PASS
:terminal-view:testDebugUnitTest -> PASS
:terminal-session-surface:testDebugUnitTest -> PASS
:app:testDebugUnitTest -> PASS
:app:assembleDebug -> PASS
:app:assembleDebugAndroidTest -> PASS
/root/termux-tui-lab make test -> PASS
```

Final local artifacts:

```text
app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk
sha256=842ca0d94a648b353328f32e309f33c2b4d41623e89c0cd8ec56b1252edf49dc
app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
sha256=d2458b9d4fe798933dc8756a490d77afe62630ebaf60682fbd3f6dcc4f0ffcdd
```

The arm64 APK contains `lib/arm64-v8a/libghostty-vt.so`. No device performance
claim is made from this local build alone.

The Android SDK reports its pre-existing platform-directory naming warning and
Gradle reports pre-existing deprecations; neither failed the build.

## Device Acceptance Gate

When ADB returns, rerun the same industrial workload rather than a screenshot:

```bash
cd /root/termux-tui-lab
tools/run-termux-industrial.sh
```

Acceptance requires all phase-1 correctness gates to remain green, including
`viewport_full_retries=0`, `black_return=false`, complete tab commits, live
pinch direction, background PTY progress, and zero main-thread parser calls.
In addition:

- `retiredPending` remains 0 and allocation counts plateau while reuse rises;
- `frame_notify_published <= frame_notify_requests`, with coalescing under load;
- foreground/background render queues remain bounded;
- `frame-publication-v4 skipped=0` for visible terminal traversals;
- no selected-frame revision stalls behind native state;
- FrameMetrics command-issue, draw, page-transition, jank, and VmRSS values are
  compared directly with `industrial-20260725-final2`.

No device performance claim is made until that run completes.

## Phase-1 Recovery Point

The exact pre-phase-2 terminal source was archived before edits:

```text
/root/.codex/checkpoints/termux-phase1-before-phase2-20260725.tar.gz
sha256=2c589987509fd464c82bc02d2eff81e541bac3e4407c90a434c558c8c4edc351
```

The archive is rooted at the repository and can be restored with:

```bash
tar -xzf /root/.codex/checkpoints/termux-phase1-before-phase2-20260725.tar.gz \
  -C /root/termux-app-source-20260306-032102
```

This recovery point includes the accepted Ghostty parser/render frontend and
phase-1 instrumentation; unrelated repository files are not part of it.

For an exact source rollback, including deletion of phase-2-only files, run:

```bash
cd /root/termux-app-source-20260306-032102
./rollback-terminal-render-phase2.sh --source-only
```

The script is explicit and was not executed during this phase.
