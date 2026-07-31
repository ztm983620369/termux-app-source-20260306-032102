# Terminal Rendering: Sora/Ghostty Pinch and Small-Font Pass

Date: 2026-07-26

## Result

This pass keeps `libghostty-vt` as the only accepted terminal/render-state authority and removes
work that was provably outside that authority. It does not use a scaled screenshot, debounce PTY
resize, defer terminal reflow until gesture end, throttle output, or accept an incomplete frame.

The source changes are:

1. Every VSync-selected integer text size still performs a real PTY window resize, SIGWINCH,
   Ghostty grid resize/reflow, retained-row rebuild, and parent-Canvas draw.
2. Ghostty-authority resize no longer also reflows the dormant owner Java `TerminalBuffer`. The
   independent compatibility journal/checkpoint remains the exact fallback source.
3. Metric changes reuse the existing renderer and a bounded 512-row object pool. Legacy ASCII
   width tables are built only if Ghostty actually falls back.
4. Eager glyph shaping is disabled during active pinch. After the first complete final frame, at
   most 256 ordinary printable ASCII runs are warmed at four runs per animation callback. Unicode,
   emoji, combining text, fallback fonts, bold, and italic remain on Android's String/Minikin path.
5. Pinch logs now separate metric, PTY/native resize/reflow, and anchor restoration time. Renderer
   logs expose scale cache bypass and incremental warm-up work.
6. A complete retained viewport can be committed while an uninterrupted PTY stream advances to a
   newer revision. Exact-revision checks remain available for tests; missing rows remain a hard
   failure.

## What Sora Is And Is Not Doing

The local source inspected is:

```text
sora-editor-0.24.3/editor/src/main/java/io/github/rosemoe/sora/widget/
  EditorTouchEventHandler.java
  EditorRenderer.java
```

Sora updates a floating paint text size and viewport anchor during scale. It bypasses cached line
`RenderNode`s while `isScaling`, then invalidates those caches at scale end. It does not own a PTY,
send SIGWINCH, resize a terminal grid, or reflow terminal scrollback. The Termux terminal module has
no Sora renderer import; Sora is used by the editor surface elsewhere in the app.

The transferable policy is therefore cache suppression during active scale and bounded rebuild
afterward. Copying Sora's visual resize alone into the terminal would be a fake terminal reflow and
is intentionally rejected.

## Ghostty Source Boundary

The complete reference checkout is pinned at:

```text
/root/ghostty-upstream-20260724
commit 15484b607eb5a518dedf1548247c923b8abaae7c
license MIT
```

The Android app already compiles and uses complete `libghostty-vt` terminal parsing/render-state
APIs. The retained dirty-row model mirrors these Ghostty renderer sources:

```text
src/renderer/cell.zig
src/renderer/generic.zig
src/renderer/row.zig
```

The desktop GPU implementation uses an atlas, per-instance cell buffers, and one instanced draw,
principally in:

```text
src/renderer/OpenGL.zig
src/renderer/opengl/RenderPass.zig
src/renderer/opengl/buffer.zig
src/renderer/shaders/glsl/cell_text.*.glsl
```

That backend is not an Android renderer. Its OpenGL/runtime and font integration target desktop
APIs; Android needs EGL lifecycle ownership, GLES-compatible shaders/buffers, Android font fallback
and color emoji handling, tab-safe surface composition, and device-loss recovery. Copying those
files into the NDK build would not produce a working or correct Android surface.

The existing `TerminalGpuRenderer` is also not production-ready: it consumes the Java compatibility
buffer, rasterizes entire String runs into an alpha atlas, and owns a `GLSurfaceView` lifecycle. It
would violate Ghostty authority, lose color-glyph semantics, and reintroduce separate-surface tab
first-frame risk if enabled as-is.

The next qualitative rendering ceiling is a Ghostty-authoritative Android atlas/batched renderer,
but it must enter behind hardware pixel differentials and the existing complete-frame lifecycle.
Until that implementation passes CJK, emoji, combining, style, cursor, selection, scroll, pinch,
reattachment, and rapid-tab gates, parent-Canvas retained rows remain the production renderer.

## Input Evidence

The pre-change device baseline is:

```text
/root/termux-tui-lab/reports/
  industrial-sora-ghostty-pinch-baseline-20260726/REPORT.md
```

At the 169x137 small-font grid under continuous flood, the captured maxima were:

```text
native delta packet             about 625,679 bytes
packet pipeline                 65,073 us average / 160,395 us max
Java packet apply               62,507 us average / 156,475 us max
parent Canvas submission       694,952 us average / 1,367,062 us max
frame p95 / p99                242.9 ms / 314 ms
```

The run failed its old initial-ready check because the PTY revision never stopped advancing, not
because the cached viewport lacked rows. This is why readiness now requires complete visible-row
coverage while exact revision remains a separate diagnostic.

The data also shows that parser replacement is no longer the main opportunity. The remaining
small-font/full-refresh ceiling is Java command construction plus HWUI Canvas submission. Skipping
the duplicate dormant Java reflow directly reduces real pinch work; it does not claim to eliminate
the Canvas ceiling.

## Correctness Gates

Local gates completed after the changes:

```text
:terminal-emulator:testDebugUnitTest                 PASS
:terminal-view:testDebugUnitTest                     PASS
:terminal-session-surface:testDebugUnitTest          PASS
:app:testDebugUnitTest                               PASS
:app:assembleDebug                                   PASS
:app:assembleDebugAndroidTest                        PASS
641 actionable tasks, 44 executed                    BUILD SUCCESSFUL
third_party/ghostty-vt/render-query status           batch
git diff --check                                      PASS
```

Built artifacts for the subsequent device gate:

```text
app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk
  sha256 839f18d986e52b469675d5d572ae7284d3bab4634bb9fc207d5091fbd12406d7
app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  sha256 4f89a2065a00be7ea3a2bf6dde5d0a4b55f95f18888145d4a98a20dc9dc5f911
```

The device viewport probe now additionally requires:

- Ghostty authority survives two native resize/reflow operations;
- both grid changes increment `dormant_java_resize_skips`;
- hardware String and ordinary-ASCII `drawGlyphs` frames remain pixel-identical;
- mixed Unicode/emoji/combining/style hardware frames remain pixel-identical;
- full viewport coverage, signed overscan, return-from-scroll, and reattachment remain complete.

The Android 16 / API 36 device gate subsequently installed both APKs on
`AWLK025930002550` and repeated `terminal_viewport` three times. All three runs produced the same
visual hashes and passed with:

```text
authority=ghostty                       PASS
ghostty_render_query=batch-v1           PASS
viewport_partial_packets=38             PASS
viewport_full_retries=0                 PASS
viewport_cache_hits=54                  PASS
dormant_java_resize_skips=2             PASS
black_return=false                      PASS
hardware_glyph_diff=verified            PASS
complex_glyph_diff=verified             PASS
glyph_shape_failures=0                  PASS
```

The first device attempt exposed a probe-only false failure: the eight-row complex sample had
already eagerly shaped its eligible ASCII commands, while the probe required another command to be
newly shaped. The corrected gate accepts an existing or newly prepared glyph cache, but still
requires a real hardware `Canvas.drawGlyphs` call and exact String/glyph pixel equality.

The complete real-PTY industrial run is:

```text
/root/termux-tui-lab/reports/
  industrial-sora-ghostty-pinch-post-20260726/REPORT.md
```

All parser, retained viewport, tab first-frame, inactive-session, live pinch, SIGWINCH, and runtime
correctness gates passed. The eight pinch samples were directionally correct and complete. Under
the 900-frame/50,000-line workload, however, steady FrameMetrics remained 148 janky frames out of
181 with p50/p95/p99 of 56.18/333.96/407.75 ms. Perfetto attributes the remaining ceiling to
RenderThread/SurfaceFlinger buffer stuffing and app deadline misses rather than main-thread parsing.
The device was at thermal status 2, so this run is valid as correctness and worst-case evidence but
not as final industrial performance certification.

## Rollback

The exact complete pre-pass checkpoint is:

```text
/root/termux-render-checkpoints/
  termux-before-sora-ghostty-pinch-complete-20260726.tar.gz
sha256 ac30d1220e31e66e8bb883d90225d3537a63e627e3824a154b7a202e79eec50c
```

One-command source rollback:

```text
./rollback-terminal-render-pinch-hotpath.sh --source-only
```

Without `--source-only`, the script builds and installs when exactly one ADB device is present. It
archives the optimized files before restoring the checkpoint; `--undo` restores that archive.
