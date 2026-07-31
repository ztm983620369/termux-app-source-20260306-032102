# Terminal Rendering Phase 3: Single Authority and Direct Parent-Canvas Rows

Date: 2026-07-26

## Objective

Phase 3 removes measured duplicate work rather than hiding it. Ghostty remains the live parser,
terminal state, scrollback, selection, input-encoding, and render-state authority. No PTY bytes are
dropped, no frame-rate cap or delayed pinch preview is introduced, and a page is never committed
before its complete native frame exists.

The phase-2 checkpoint is preserved at:

```text
/root/.codex/checkpoints/termux-phase2-before-phase3-20260726.tar.gz
sha256=7024d194ff591298e18492747e6ece427ae293d92fb6bb5ecbb9a43c6aa94eea
```

One-command source rollback is available through
`./rollback-terminal-render-phase3.sh --source-only`. The script verifies the archive hash and
creates an undo archive before changing files.

## Changes

### One live parser

The former compatibility checkpoint parsed the same healthy PTY stream into a second Java terminal.
Perfetto showed `TermuxJavaCheckpointParse` batches as long as 2.76 seconds, with the worker
competing against Ghostty, the UI thread, and RenderThread.

Healthy output now has one parser. An ordered PTY/resize/reset/theme journal is written through a
256 KiB buffered temporary file and is not parsed into Java state. It is sealed only on explicit
fallback or at the 256 MiB safety boundary. Diagnostics identify this as `mode=disk-lazy`; the
normal 80 MiB device run emitted no Java-checkpoint trace or log.

Scroll and full-redraw signals are atomically consumed by `TerminalView`, so the UI never waits on
the PTY mutation monitor and a concurrent update cannot be cleared accidentally.

### Render-state recovery without terminal rollback

A render-only failure transactionally recreates Ghostty's `RenderState`, row iterator, and row-cell
query objects under the native backend mutex. Parser state, grids, scrollback, effects, and PTY
ordering remain live. The old render objects are freed only after the replacement is fully valid.

The debug device probe deliberately closes the Java render gate, performs this recovery, and then
requires a complete full packet containing the styled cell written before the failure. Device
evidence reports `ghostty_render_recovery=in-place`.

### Same-VSync dirty-row publication

The native delta is decoded during the already-coalesced Choreographer callback. Its changed-row
range is published immediately in that VSync instead of scheduling another animation callback.
`TerminalRenderDamageTracker` unions valid row damage and escalates to a full invalidation on grid,
viewport, background, selection, or full-packet transitions. Canvas clipping limits parent drawing
to the invalid row range.

### Direct parent-Canvas retained rows

All supported Android versions retain immutable row commands and draw them directly on the terminal
parent hardware `Canvas`. Dirty rows rebuild their existing `RowDisplay` in place; command pools and
the spare row-object pool remain bounded. Three screenfuls of CPU commands preserve exact reversal
and signed fractional overscan behavior.

The per-row Android `RenderNode` experiment was rejected from the accepted source. Besides worsening
HWUI command submission in the captured device run, nested row composition is the known root cause
of the earlier missing-middle-row/black-tab failure. Legacy RenderNode counters remain logged as
explicit zeroes; `directCanvasRows` records the production draw path.

### One UTF-8 decode per dirty row

The ABI-2 packet previously allocated one byte array and one String, then invoked `measureText`, for
every color/style fragment. A colorful small-font TUI can contain thousands of such fragments per
frame.

Each dirty row now copies its validated UTF-8 arena once into reusable storage and creates one shared
String. Text commands hold UTF-16 ranges into that String. Printable ASCII monospace runs use the
cell width already resolved by Ghostty, avoiding duplicate shaping measurement; Unicode, emoji,
combining sequences, wide cells, bold, italic, and decorations keep the exact measured path.
UTF-8 byte-to-UTF-16 boundary tests cover combining marks, CJK, and surrogate pairs.

### Cached glyph submission without a nested surface

Android 13 and newer shape ordinary printable monospace ASCII runs once into reusable glyph IDs,
positions, and font objects. Subsequent scroll/fling redraws submit those cached glyphs directly to
the terminal parent hardware `Canvas`; they do not reshape an unchanged ASCII String on every finger
frame. Complex Unicode, fallback fonts, synthetic bold/italic, older Android, software Canvas, and
any failed shaping operation use the existing Minikin String draw in the same frame. This boundary
is enforced by hardware-buffer pixel differentials containing CJK, emoji, combining marks, box
drawing, reverse video, underlines, and mixed ASCII. Pure ASCII-space runs submit no glyph draw at
all. Counters expose shaped runs/glyphs, failures, cumulative shaping time, hardware glyph draws,
String fallback draws, and skipped blank runs.

### Reproducible device test conditions

The industrial entry point now accepts `--baseline-text-size` (42 px by default), applies it before
creating test PTYs, records both baseline and user value, restores the user's value afterward, and
closes only the tab created by the benchmark. Earlier runs used different grids and could not be
compared honestly.

Perfetto collection waits for a non-zero stable trace file before pulling. Its SQL also identifies
the app through the main-thread name when a ring-buffer wrap loses process metadata. The analyzer
marks thermal status 2 or 3 as a high-severity condition that invalidates absolute performance
comparison.

## Correctness Evidence

On device `AWLK025930002550`, repeated viewport probes report:

```text
status=PASS
bottom_hash=3389838617715003512
traversal_hash=4542994132022840545
viewport_partial_packets=88
viewport_full_retries=0
black_return=false
ghostty_render_recovery=in-place
hardware_glyph_diff=verified
hardware_glyph_hash=50599319752150277
hardware_glyph_draws=33
shaped_glyphs=95566
glyph_shape_failures=0
complex_glyph_diff=verified
complex_glyph_hash=5929323017167795947
```

The hashes remained identical after up/down traversal, detach/reattach, signed overscan, native
render recovery, direct parent-Canvas drawing, row-shared UTF-8 decoding, and glyph preparation.
Each final probe also renders String-reference and glyph-fast-path frames through a real
`HardwareBufferRenderer`, waits for the GPU fence, reads pixels back, and compares the complete
frame. Three repeated runs produced identical software, ASCII-hardware, and complex-hardware hashes;
the ASCII path executed 33 real `drawGlyphs` calls with 95,566 prepared glyphs and zero shaping
failures per run.

The initial unrestricted glyph experiment was correctly rejected by this gate: a mixed fallback-font
frame differed by 53 visible pixels. Production was narrowed to ordinary non-bold/non-italic
printable ASCII, while CJK, emoji, combining marks, fallback fonts, and synthetic styles remain on
the exact String path. The mixed frame then became pixel-identical. All 17/17 or 13/13 tab selections
in the completed industrial runs had complete first frames; inactive PTYs continued advancing; eight
pinch directions caused real resize/reflow; parser main-thread calls remained zero.

## Performance Evidence and Boundary

The fixed 47x41 grid processes about 81 MiB across 1604 emitted terminal frames. Experimental
per-row RenderNode runs demonstrated that bounding display lists reduced their residency, but that
branch is not accepted as a production optimization because its composition correctness contract is
invalid. The reports are retained as rejected-experiment evidence, not as a performance claim.

Absolute frame comparisons from the current session are not accepted because every captured run
started at Android `Thermal Status=3` (severe throttling, skin near 44 C). Those reports remain valid
for correctness, memory bounds, parser authority, and worst-case attribution:

```text
/root/termux-tui-lab/reports/industrial-phase3-lazy42-20260726-adb3/REPORT.md
/root/termux-tui-lab/reports/industrial-phase3-gpubounded-nolayer42-20260726-adb5/REPORT.md
/root/termux-tui-lab/reports/industrial-phase3-inplace42-20260726-adb6/REPORT.md
```

Perfetto still attributes the extreme full-screen color churn to the app main thread and HWUI
RenderThread. Cached `drawGlyphs` removes repeated shaping from unchanged scrolling rows but does not
claim to equal a native glyph-atlas renderer when every cell changes every frame. The next ceiling is
a correctly lifecycle-integrated batched atlas surface, not additional invalidation delays or fake
visual previews. The repository's dormant `TerminalGpuRenderer` is not that solution: it has no
production caller, reads the Java compatibility buffer, and would violate Ghostty authority if
enabled as-is.

## Verification

Completed gates include:

```text
:terminal-emulator:testDebugUnitTest                 PASS
:terminal-view:testDebugUnitTest                     PASS
:app:compileDebugJavaWithJavac                       PASS
:app:assembleDebug                                   PASS
:app:assembleDebugAndroidTest                        PASS
four ABI ndkBuild with -Werror                       PASS
industrial analyzer tests                            PASS (10)
device viewport probe and native recovery            PASS
industrial functional workload                       PASS
```

The final absolute performance run must use the same APK, 42 px baseline, 47x41 grid, and stress
profile after pre-run thermal status returns to 0 or 1.
