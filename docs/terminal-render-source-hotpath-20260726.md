# Terminal Rendering Source Hot-Path Optimization

Date: 2026-07-26

## Scope

This pass responds to the measured frame cost and the reported small-font scrolling regression.
It does not add a frame cap, output throttle, deferred visual preview, parser shortcut, or incomplete
frame fallback. Ghostty remains the terminal and render-state authority, and retained rows still
draw directly into the terminal parent Canvas.

The input evidence is:

```text
/root/termux-tui-lab/reports/
  industrial-industrial-phase3-atomic-cache42-20260726-adb13/REPORT.md
```

At the fixed 47x41 baseline that report recorded:

```text
PTY bytes parsed off main thread     14,645,032
Ghostty parser/render authority      true / true
viewport partial/cache/retries       38 / 54 / 0
Canvas Draw p50 / p95                2.33 / 15.35 ms
Command issue p50 / p95              3.58 / 32.78 ms
Total p50 / p95                      49.31 / 321.12 ms
thermal status                       2 (moderate throttling)
```

The report proves authority, completeness, and retained viewport behavior. Thermal status 2 means
the absolute frame numbers are not an accepted before/after baseline. They still show that parser
work is off-main and that CPU command preparation/submission, rather than GPU execution alone, is
the next source target. The user-observed regression at a smaller font adds a critical scaling
signal: work proportional to visible rows/cells must not perform speculative cache setup.

## Source Changes

### Same-cycle finger and fling invalidation

`TerminalView.setViewportPositionPixels()` no longer updates the viewport in one animation callback
and posts a second animation callback merely to invalidate. It invalidates in the current UI/
animation cycle. Android still coalesces traversal, while `renderFrame()` remains the complete-frame
gate. The new `viewportImmediate` counter separates this path from native screen publication.

This removes one scheduling hop. It does not predict a scroll position or show a translated stale
bitmap.

### Adaptive glyph-cache construction

Cached glyph IDs are optional acceleration; they are not needed for correct pixels. Eager shaping
now runs only for a low-churn packet:

- at most 8 changed rows;
- changed rows at most one quarter of a normal grid, except a complete grid of at most 8 rows;
- at most 32 shaped text runs per packet;
- only printable, non-bold, non-italic ASCII runs of at least 8 glyphs.

Large/full/high-churn packets and short colorful TUI fragments use the exact String Canvas path in
the same frame. No cells or frames are omitted. Counters expose cache/bypass packets and bypassed
runs so the policy can be validated rather than assumed.

Viewport-only packets are an additional hard boundary: newly exposed rows during a direct drag or
fling never pay speculative glyph shaping on that motion frame. They use the exact String path and
can be shaped later by a content packet, while `glyphCacheViewportBypassPackets` makes this choice
visible in diagnostics.

### One row-table pass and no per-row ByteBuffer object

`GhosttyRenderDelta.copyRowCellsAndGetUtf8Length()` returns the contiguous UTF-8 arena length while
performing the mandatory six-field cell-table copy. The renderer no longer scans every cell again
to rediscover a range already defined by the native ABI.

`copyUtf8Range()` now saves/restores the callback-scoped direct buffer position around the
API-1 bulk read. It no longer creates a temporary `ByteBuffer.duplicate()` object for every dirty
row and remains compatible with the project's API 23 minimum.

The row directory's next-payload lookup uses a binary search, and UTF-8 row classification (blank
versus printable ASCII versus Unicode) is one byte pass instead of two. These are CPU-only changes;
the UTF-8 validation path and all non-ASCII handling remain unchanged.

### ASCII and blank-row specialization

A dirty row is classified once:

- printable ASCII uses byte offsets directly as UTF-16 indices and skips the UTF-8 boundary table;
- an ASCII-space-only row skips String construction and glyph submission;
- Unicode, emoji, combining marks, wide cells, and malformed boundaries retain the validated UTF-8
  mapping and measured String path.

This specialization changes CPU work, not text content or positioning.

### Fewer Java cell passes and bounded allocation growth

Selection and cursor ranges are resolved once per row. Color transformation and background-span
generation share one cell pass. `RowDisplay` command arrays reserve a column-derived capacity capped
at 256 entries, avoiding repeated growth/copy cycles on colorful small-font grids while keeping
memory bounded.

### Native color multi-query

When a Ghostty cell explicitly contains both foreground and background colors, the NDK bridge now
retrieves them in one `row_cells_get_multi()` call. Cells with no explicit color, or only one color,
retain the original selective query and default-color behavior. All four Android ABIs compile with
`-Werror`.

## Diagnostics

`TermuxRenderV2` now reports average/maximum microseconds for:

```text
packetPipelineUs  native query plus synchronous Java consumer
packetApplyUs     Java delta validation/cache application
rowBuildUs        average changed row / maximum row-build batch
canvasDrawUs      direct parent-Canvas command submission
```

It also reports ASCII/Unicode/blank rows, String decodes, adaptive glyph-cache decisions, retained
viewport hits, and native packet skips. The industrial analyzer parses the four stage timings and
`glyphCachePackets`, `glyphCacheBypassPackets`, `glyphCacheViewportBypassPackets`, and
`glyphCacheBypassedRuns` into the generated report. This is deliberately low-overhead: timing is
sampled per packet/batch/frame, not per cell or glyph.

## Local Verification

Completed without ADB:

```text
:terminal-emulator:testDebugUnitTest          PASS
:terminal-view:testDebugUnitTest              PASS
:terminal-session-surface:testDebugUnitTest   PASS
:app:testDebugUnitTest                        PASS
:app:assembleDebug                            PASS
:app:assembleDebugAndroidTest                 PASS
four ABI ndkBuild with -Wall -Wextra -Werror  PASS
industrial analyzer tests                     PASS (11)
runner shell syntax                            PASS
git diff whitespace check                      PASS
```

Artifacts:

```text
app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk
sha256=3d22f28cecacde565e82e631eeda4c2e171b61e7163f8107814ee4d59460e489

app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
sha256=4f89a2065a00be7ea3a2bf6dde5d0a4b55f95f18888145d4a98a20dc9dc5f911
```

## Rollback

The exact pre-hot-path source checkpoint is:

```text
/root/termux-render-checkpoints/termux-phase3-before-source-hotpath-20260726.tar.gz
sha256=c69217593a2bcf7019b596e79385a7198e5099b2ac298c85a1c3a30f675bc29b
```

One-command rollback:

```text
./rollback-terminal-render-source-hotpath.sh
```

Use `--source-only` to skip build/install. Every rollback first archives the optimized files; use
`--undo` to restore that archive.

## Required Device Gate

No ADB device was available during this source pass, so no runtime improvement is claimed yet. The
next connected-device run must use the current industrial entry point and collect two comparable
profiles under thermal status 0 or 1:

1. fixed 42 px / 47x41 baseline for continuity with the accepted report;
2. the user's small-font grid with static transcript drag, fling, `seq 1 500`, high-frequency TUI,
   live pinch, and repeated terminal-tab transitions.

Acceptance requires complete first frames, zero viewport full retries, zero parser main-thread
calls, exact viewport/hardware pixel probes, real-time touch tracking, and lower row-build/pipeline
and frame-tail measurements. Any pixel or completeness regression rejects this optimization even if
aggregate frame time improves.
