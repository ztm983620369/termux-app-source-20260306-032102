# Open-Source Terminal Rendering Integration

Date: 2026-07-26

Scope: `terminal-view` retained HWUI renderer after the existing libghostty-vt
parser and native render-delta pipeline. The parser owner was not changed in this
pass.

## Executive Result

The production-safe part of the open-source research is now enabled in the Android
renderer:

- compatible ordinary ASCII runs are submitted through one ordered API 33+
  `Canvas.drawGlyphs` batch while preserving painter order;
- shaped glyph arrays use a bounded exact-text cache with four-probe replacement;
- idle cache warming is amortized over frame callbacks and is cancelled when content,
  pinch, scroll, or lifecycle state changes;
- every batch failure disables only the batch path and redraws the complete frame through
  the existing String path in the same Canvas pass;
- the device probe proves a real cross-run reduction, not only a nonzero glyph call.

This is a real implementation and not a frame-delay, dropped-output, or fake-scale
optimization. It does not yet claim that the complete 90 Hz extreme PTY workload is
within budget. The previous industrial report still attributes that ceiling to Android
HWUI RenderThread/SurfaceFlinger composition under saturation; this pass was validated
with the focused hardware differential probe, while the phone was at 10% battery and
44-47 C, so a new full stress run would not be a controlled performance comparison.

## Research Set

The following repositories were cloned outside the application tree so their source and
licenses remain auditable. Commits are pinned rather than tracking a moving branch.

| Project | Pinned commit | License | Mechanism used or rejected |
| --- | --- | --- | --- |
| [Ghostty](https://github.com/ghostty-org/ghostty) | `15484b607eb5a518dedf1548247c923b8abaae7c` | MIT | retained dirty rows, foreground runs, one instanced submission; adapted to the existing Java/HWUI owner |
| [Rio](https://github.com/raphamorim/rio) | `d656326020ffe5959e221af7a7d1d8d82a6ab2db` | MIT | compact row/background state and size-bucket thinking; Rust/wgpu backend was not copied into Android |
| [kitty](https://github.com/kovidgoyal/kitty) | `9dca948e9bec3c926ab3370f2cd10f9b9b10821f` | GPL-3.0 | texture-array and instanced-cell concepts; desktop OpenGL/shader/resource lifetime is not an Android drop-in |
| [xterm.js](https://github.com/xtermjs/xterm.js) | `699f5537b0232e444cb98261b8b3991c3cfecb5e` | MIT | changed-row render model and bounded atlas/cache eviction principles |
| [Contour](https://github.com/contour-terminal/contour) | `060c6462bc64032b2e88977e12b46612862ed5dd` | Apache-2.0 | transactional prepare/render and detach/recreate lifecycle invariants |
| [glyphon](https://github.com/grovesNL/glyphon) | `49dc8f7bafa8091f4d71521fd62ee6f647b556f5` | MIT/Apache-2.0/Zlib | generation-safe cache lifetime and bounded LRU-style eviction |
| [WezTerm](https://github.com/wezterm/wezterm) | `76b606ec597a3c0263fa60321548637451c0a547` | MIT | fallback/grapheme correctness review; no coupled renderer transplant |
| [Alacritty](https://github.com/alacritty/alacritty) | `bdb72b32eeb074e3a0b8559d8ccac458237474a3` | Apache-2.0 | GLES portability reference; full-grid collection was not selected for this retained path |
| [wgpu](https://github.com/gfx-rs/wgpu) | `83b9fa1661a7ecb7c98e3c48ef400ef7d4d2b40a` | MIT/Apache-2.0 | future Vulkan/Android backend candidate; not enabled without a complete surface/lifecycle gate |

Reference checkout root: `/root/terminal-render-reference-20260726`.

No source file was copied verbatim from a donor into the Android app. The selected
algorithms were re-expressed against the existing Termux contracts so that Ghostty
remains the sole parser/render-state authority and the existing String fallback remains
available for complex fonts, styles, and API levels below 33.

## Implemented Path

### Ordered glyph batching

`terminal-view/src/main/java/com/termux/view/GhosttyRenderNodeRenderer.java` now owns an
API 33+ `Api33GlyphBatch`. It accepts only prepared, unscaled, non-bold, non-italic
ordinary ASCII commands. It flushes at every semantic painter boundary:

- a background rectangle;
- a decoration rectangle;
- an ineligible or scaled text command;
- a color or Android `Font` change;
- the end of the visible row range.

Positions are converted to absolute Canvas coordinates before submission. This allows
compatible runs from adjacent rows to share a submission without changing z-order.
The batch is used only on a hardware Canvas. A `RuntimeException` or `LinkageError`
marks batching unhealthy for that renderer instance, clears the Canvas clip, and redraws
the full visible frame through the String path. The fallback is observable through
`glyphBatchFallbackFrames` and never silently drops a row.

### Shape cache

The renderer keeps 1024 entries with four linear probes. A key is an FNV-style hash plus
an exact UTF-16 range comparison, so hash collisions cannot produce a wrong glyph run.
Entries hold only the requested substring, glyph IDs, positions, and resolved Android
fonts. The cache is cleared on metric/typeface changes and session reset. A retained-row
command receives a monotonically increasing generation; idle warming refuses to touch a
command from an older generation.

The cache is deliberately bounded and its estimated memory is included in renderer
diagnostics. Eviction is approximate LRU within the probe window, which keeps lookup
constant-bounded and avoids an unbounded global map on long-running terminals.

### Idle warming

After a complete Ghostty frame is presented, API 33+ devices require two stable animation
frames before warming at most four runs per callback. Warming is skipped during pinch,
direct finger scroll, fling, pending commit, incomplete viewport coverage, or active
frame invalidation. Any new retained-command generation cancels the work. It prepares
discardable glyph arrays only; it does not delay PTY output, resize, reflow, or frame
publication.

## Device Evidence

Device: `AWLK025930002550`, Android API 36, arm64-v8a.

The focused instrumentation action was run three times after installing the rebuilt app
and test APK. All three produced the same values:

```text
TERMUX_VIEWPORT_PROBE status=PASS
generated_lines=500 transcript_rows=467 rendered_frames=82
viewport_partial_packets=38 viewport_full_retries=0 viewport_cache_hits=54
authority=ghostty black_return=false frame_ready_lifecycle=true reattachment=true
hardware_glyph_diff=verified hardware_glyph_batch_fallbacks=0
batch_compression=verified batch_compression_calls=1
batch_compression_commands=33 batch_compression_glyphs=2013
complex_glyph_diff=verified glyph_shape_failures=0
ghostty_render_query=batch-v1 ghostty_render_packets=snapshot-v1,delta-v2
```

The compression scenario is intentionally separate from the colored scrollback scenario:
it renders 34 independent default-background ASCII rows. The String and glyph frame
hashes must match, and 33 compatible runs must be submitted in one batch. This proves
the new cross-run reduction rather than merely proving that `drawGlyphs` exists.

The production scrollback scenario also compares the full hardware frame hashes:

```text
hardware_glyph_hash=50599319752150277
hardware_glyph_batch_calls=33
hardware_glyph_batched_commands=33
hardware_glyph_batched_glyphs=2343
hardware_glyph_batch_fallbacks=0
complex_glyph_hash=5929323017167795947
```

### Real Activity Smoke

The same rebuilt APK was then exercised through the real Termux Activity and PTY entry
point at a deliberately reduced load (`64` full frames, `1000` burst lines, stress `1`,
no Perfetto) because the device battery was at 9%. Instrumentation itself passed with
`selections=13/0`, `warnings=0`, and `errors=0`. The recovered `TerminalView` diagnostics
showed the production path, rather than the direct probe only:

```text
grid=107x93 complete=true skipped=0
glyphBatchHealthy=true glyphBatchCalls=45 glyphBatchedCommands=45
glyphBatchedGlyphs=1446 glyphBatchFallbacks=0
glyphShapeCacheHits=23 glyphShapeCacheMisses=47 glyphShapeCacheRestoredGlyphs=678
viewportPartial=9 viewportRetries=0
```

The smoke report is retained at
`/root/termux-tui-lab/reports/industrial-opensource-glyph-batch-smoke-20260726/REPORT.md`.
Its verdict is intentionally `ATTENTION REQUIRED`: 56 of 94 steady-workload frames were
janky and the device thermal sampler reported status 3. This is useful evidence that the
real path is active, but it is not a controlled claim of end-to-end performance gain.

## Verification Gates

Passed locally:

```text
./gradlew --no-daemon :terminal-emulator:testDebugUnitTest \
  :terminal-view:testDebugUnitTest \
  :terminal-session-surface:testDebugUnitTest \
  :app:testDebugUnitTest \
  :app:assembleDebug \
  :app:assembleDebugAndroidTest
python3 -m unittest tools/test_analyze_termux_industrial.py
git diff --check
```

The app and Android test APKs were installed on the device, and the viewport probe passed
three consecutive times. `third_party/ghostty-vt/render-query status` reports `batch`.

The current arm64 debug APK SHA-256 is recorded at handoff time with:

```text
29855208e8714f6b18d64f7dd64576c1a405ffd241539bc88bc3f348779f8742  app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk
4f89a2065a00be7ea3a2bf6dde5d0a4b55f95f18888145d4a98a20dc9dc5f911  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

The full industrial PTY/Perfetto workload was not rerun in this pass because the connected
device was at 10% battery and 44 C after the focused probes. The prior controlled report
remains the baseline for end-to-end frame pacing; its `ATTENTION REQUIRED` result is not
overwritten by this focused correctness/batch proof.

## Deliberately Not Enabled

The repository also contains a `TerminalGpuRenderer` experiment, but it reads a separate
Java compatibility buffer and owns an independent `GLSurfaceView`. Enabling it now would
create a second parser/render authority and reintroduce the tab/black-frame lifecycle bug.
Likewise, importing Rio/glyphon/wgpu wholesale would add a Rust GPU surface, font atlas,
and Android EGL/Vulkan lifecycle that has not passed the exact String-vs-GPU differential
gate. [wgpu](https://github.com/gfx-rs/wgpu) is a viable later Android Vulkan foundation,
but it is a separate phase, not a hidden fallback in this one.

## Rollback

The one-command rollback entry point is:

```sh
./rollback-terminal-render-opensource-batch.sh --source-only
```

It verifies and restores these immutable checkpoints:

```text
termux-before-opensource-glyph-batching-20260726.tar.gz
  ac74e6f9dae0859581d74a01f81b3b804df4147efbdcec903cf864b929253fe1
termux-before-idle-glyph-warmup-20260726.tar.gz
  6c11a15d30af81fe52fb2cda5df419bdac731af9e3019ff991af6dbec44a2f2e
termux-before-opensource-glyph-probe-20260726.tar.gz
  aa360bd8466798dadadfd9ddb2f1d44981304b6cd5332b5d0cb0b96eb82f6827
```

The script archives the optimized five-file set before restoring it. The latest archive
can be restored with:

```sh
./rollback-terminal-render-opensource-batch.sh --undo --source-only
```

Without `--source-only`, the script builds and installs the restored APK only when exactly
one ADB device is available. It does not touch unrelated dirty files or parser checkpoints.

## Next Engineering Gate

The next performance phase should be a separate Android GPU renderer project with these
non-negotiable gates: Ghostty-only state input, retained row generations, atlas generation
validation, tab first-frame readiness, exact full-frame differential hashes for ASCII and
complex graphemes, and Perfetto/FrameTimeline comparison against the current HWUI path.
Until that exists, the current ordered HWUI batch is the highest-risk-adjusted improvement
that can be enabled without sacrificing terminal correctness.
