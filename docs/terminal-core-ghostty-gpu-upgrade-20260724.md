# Termux terminal core: authoritative Ghostty VT + retained Android renderer V2

Date: 2026-07-24

## Result

Normal terminal sessions use the complete pinned `libghostty-vt` as the authoritative parser,
terminal-state owner, selection engine, input encoder, and render-state provider. The Java parser
is no longer executed for healthy PTY traffic. Rendering consumes a native ABI-2 dirty-row packet
into retained row commands drawn directly on the terminal parent hardware `Canvas`; live pinch
performs real Ghostty resize/reflow on each VSync, and local scrollback uses a fractional-pixel
viewport with an absolute finger anchor.

The integrated upstream is Ghostty commit
`15484b607eb5a518dedf1548247c923b8abaae7c`, built with Zig 0.16, Android NDK r29,
`ReleaseFast`, SIMD enabled, API 23, and 16 KiB ELF LOAD alignment for:

- `arm64-v8a`
- `armeabi-v7a`
- `x86`
- `x86_64`

Exact provenance, hashes, build command, dependency notices, and ABI policy are in
`third_party/ghostty-vt/UPSTREAM.md` and `third_party/ghostty-vt/BINARIES.sha256`.

## Production data flow

```text
PTY reader / parser thread (`TermSessionInputReader`)
   |
   v
libghostty-vt (one parse)
   |-- cached 22-value state packet --> session/view modes, cursor, colors, history
   |-- native effects -------------> PTY replies, bell, title, clipboard, host OSC
   |-- Ghostty encoders <----------- key/text/mouse/focus/paste input
   |-- selection APIs -------------> copy, transcript, word, URL, OSC 8
   |-- ABI-2 dirty-row packet ------> retained Android row display lists
   |                                  |-- clean rows: zero decode/copy
   |                                  |-- changed rows: rebuild one row
   |                                  `-- moved viewport: exposed rows only; overlap retained
   |-- resize/reflow <-------------- live pinch frame (real grid change)
   `-- state/render APIs -----------> selection, cursor, scrollback, diagnostics

disk-lazy PTY journal (not parsed) --> explicit rollback/failure or 256 MiB checkpoint
```

There is one JNI parse call per PTY chunk, no JNI call per cell, and no Java parser call in the
accepted path. Healthy PTY parsing never executes on Android's main thread.

## Threading and stream ordering

- each session's blocking PTY reader is also its dedicated Ghostty parser thread, eliminating the
  former reader-to-main parse queue and its UI-thread byte budget;
- native state mutation, resize, reset, theme journal events, lazy journal sealing, and
  authority transitions are serialized at the emulator boundary;
- write-PTY protocol replies go directly to the thread-safe output queue to preserve response
  ordering, while title, bell, clipboard, colors, and host callbacks are posted to the Android main
  looper before application code runs;
- Java parsing remains deliberately main-thread confined, but its queue is reachable only after an
  explicit rollback or native failure; and
- the process waiter joins the PTY reader through EOF before appending the synthetic exit marker,
  so neither the final kernel PTY bytes nor `[Process completed ...]` can overtake each other.

Perfetto/system tracing exposes `TermuxGhosttyPtyParse` on the reader thread and
`TermuxJavaFallbackPtyParse` only for the explicit Java fallback queue, making the thread boundary
observable outside the instrumentation counters.

## Implemented terminal contract

Parser and state:

- complete Ghostty VT parsing, Unicode grapheme state, styles, alternate screen, reflow/resize,
  scrollback, cursor, modes, palette, synchronized output, and VT error reporting;
- cached state returned in the same native call as write/resize/config mutations;
- Termux logical history limit preserved even though the pinned Ghostty implementation accounts
  `max_scrollback` in bytes rather than the line unit stated in its pinned C header;
- configured block/underline/bar cursor translated once at the ABI boundary and applied as the
  Ghostty DECSCUSR default.

Effects and host integration:

- `write_pty`, bell, title, OSC 52 clipboard, size, color scheme, device attributes, XTVERSION,
  dynamic palette notification, and Termux OSC 8900;
- OSC 8900 parsing is chunk-boundary safe, supports BEL and ST terminators, and is bounded to 8192
  bytes;
- callbacks are synchronous with Ghostty's write, matching terminal reply ordering.

Input:

- Ghostty key encoder for special keys and application modes;
- physical Android key mapping plus UTF-8, unshifted codepoint, modifiers, and press/repeat/release
  action for Kitty keyboard protocol;
- Ghostty mouse, focus, and bracketed-paste encoders.

Text and selection:

- native transcript/selection formatter;
- Ghostty word selection and wide-grapheme endpoint snapping;
- direct OSC 8 hyperlink lookup and bounded textual URL context;
- selection preview, copy, transcript sharing, and URL selection no longer materialize Java state.

Rendering and interaction:

- native packet ABI 2 begins with a per-viewport-row directory; zero offsets retain clean rows,
  while present rows carry fixed 24-byte cell records and a compact UTF-8 arena;
- retained row commands are drawn directly into the terminal parent hardware `Canvas`; nested
  per-row `RenderNode` composition is deliberately forbidden because it previously dropped middle
  row bands during page switching, while the Java compatibility renderer remains failure-only;
- row commands retain three screens for exact reverse-scroll and signed-overscan behavior; dirty
  visible rows rebuild their existing row object in place and command/row pools remain bounded;
- each changed row copies and decodes its UTF-8 arena once; all color/style commands reference
  UTF-16 ranges in that shared row String, while printable ASCII monospace runs skip redundant
  `measureText` and complex Unicode keeps the measured path;
- on Android 13 and newer, ordinary printable monospace ASCII runs are shaped once into reusable
  glyph IDs, positions, and font references; repeated parent-Canvas draws during finger scroll call
  `drawGlyphs` directly. Complex Unicode, fallback fonts, synthetic bold/italic, older/software
  canvases, and any shaping failure retain the exact String path in the same frame; pure ASCII-space
  runs submit no glyph draw at all;
- a stable scrollback viewport move encodes only newly exposed logical rows plus cursor-sensitive
  rows; overlapping rows retain their existing display lists. Java verifies that every visible row
  exists and retries once with an atomic full frame on any cache gap, preserving black-return
  correctness without paying a full 159-row decode on each small-font row crossing;
- direct touch uses an absolute `viewportAtDown + downY - currentY` mapping rather than adding
  gesture deltas. It has no accumulation drift, ignores only the initial touch slop, aborts an old
  fling on down, and transfers to the existing inertia only after up;
- sub-row scrolling uses signed viewport coordinates: moving toward history uses bottom overscan;
  moving toward the live edge uses top overscan. Each normal MOVE reuses retained row commands and
  changes only their parent-Canvas translation; native row decoding is needed only when crossing a
  terminal row boundary;
- pinch applies real font metrics, grid resize, Ghostty reflow, and focus-anchor correction at most
  once per VSync. Preferences are committed only at gesture end; there is no shader-only preview;
- replaced rows recycle plain Java commands synchronously because they own no nested GPU resource;
  session switches and detached views clear retained commands. Reattachment and direct
  visibility changes request an atomic native full frame before contributing pixels;
- horizontal session transitions are direction-aware. Only the current page and the page actually
  entering the viewport may consume live tmux/TUI updates. A target with a current retained frame is
  moved with zero redraw, a dirty target consumes one coalesced delta at the next VSync, and only a
  missing/disposed/size-changed target requests a full frame. Repeated capture/drag/settle callbacks
  are deduplicated per transition, rapid reversals prepare each real target once, background updates
  collapse to one pending flag, and ViewPager retains only one page on each side. Animated
  programmatic jumps are limited to adjacent pages so a many-tab jump never traverses unprepared
  intermediate TUI surfaces; and
- `TermuxRenderV2` metrics report packets, full frames, native/decoded/retained rows, parent-Canvas
  row draws, row UTF-8 decoding, blank-run skips, pre-shaped glyphs, glyph/String Canvas draws,
  shaping failures/time, grid, viewport, pixel offset, scrollback, visual rows, viewport rebuilds,
  full-frame requests/completions, and average render time. The legacy RenderNode fields remain
  explicitly zero so device reports prove that nested composition is absent. A successful
  reactivation emits `full-frame-ready`; each direct scroll emits
  `finger-scroll-v2` with requested/applied pixel deltas and tracking error. Each horizontal
  transaction emits `page-transition-v2` with zero-redraw, dirty-delta, full-frame, direction-change,
  page-count, offscreen-limit, and elapsed-time evidence.

Lifecycle and failure behavior:

- native handle owns and frees terminal, render state, iterators, input encoders, JNI weak callback,
  and scratch storage;
- explicit close paths plus a last-resort Java finalizer prevent abandoned-session native leaks;
- any library/symbol/init/write/resize/config failure activates exact Java compatibility state;
- a render-only failure first transactionally recreates Ghostty's render state, row iterator, and
  row-cell query objects from the still-live terminal; parser, grids, scrollback, and effects remain
  untouched, and Java replay is used only if this in-place recovery also fails;
- no Shadow registry, plugin lifecycle, or plugin storage participates in this terminal chain.

## Rollback

Every authoritative session records an ordered raw PTY/resize/reset/theme journal in the app's
temporary storage. Writes use a 256 KiB buffered stream and bounded 64 KiB records; no second
in-memory byte history or Java terminal exists on the healthy path. The file is sealed at 256 MiB
or on explicit rollback. Only then does a dedicated worker replay it once into a Java-only emulator.
Rollback waits for that exact ordered checkpoint, restores the live state, then closes Ghostty.
Normal output is therefore parsed exactly once while preserving a deterministic escape hatch.

Immediate rollback for the running app is one command typed in any Termux terminal:

```sh
printf '\033]8900;terminal-backend;java\033\\'
```

The native callback only flips the process gate while Ghostty owns its mutex; immediately after the
same native write returns, Termux reconstructs Java state and closes the native session safely.
Restarting the app restores the compiled default.

Persistent source/build default rollback is also one command:

```sh
third_party/ghostty-vt/terminal-backend java
```

Restore the authoritative backend with:

```sh
third_party/ghostty-vt/terminal-backend ghostty
```

That tool applies or reverses a one-line patch only. It does not reset the repository and cannot
overwrite unrelated dirty-worktree changes.

The programmatic gate remains available as
`TerminalEmulator.setGhosttyProductionEnabled(false)`.

The V2 interaction/renderer frontend has a separate one-command rollback. It saves the exact V2
sources first, restores only the frontend/gesture files to pinned GitHub commit
`0b988510954e2ac5442409ad81d321c0f506f156`, builds, and installs when one adb device is online.
The Ghostty parser backend and unrelated dirty-worktree files are not touched:

```sh
./rollback-terminal-render-v2.sh
```

Undo that rollback from its automatically recorded archive with:

```sh
./rollback-terminal-render-v2.sh --undo
```

Pass `--source-only` to either command to skip build/install.

## Verification

Local core suites:

```sh
./gradlew :terminal-emulator:testDebugUnitTest :terminal-view:testDebugUnitTest \
  :terminal-session-surface:testDebugUnitTest
```

Four-ABI compile and APK packaging:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

The debug device regression generates 500 rows, traverses bottom/-1/-8/-19/-47/-120 and back for
82 frames, rejects any blank viewport, validates both signed overscan directions, simulates a
renderer detach/reattach, requires partial viewport packets with retained rows and zero cache-gap
retries, and requires the returned bottom bitmap to exactly equal the initial bottom bitmap:

```sh
adb shell am instrument -w -r -e action terminal_viewport \
  com.termux.test/com.termux.shadow.ShadowProbeInstrumentation
```

The `terminal_viewport` branch returns before the existing runner initializes any unrelated platform
state. The Honor AGI-AN00 arm64 regression passed with 467 scrollback rows, bottom hash
`3389838617715003512`, 202556 visual pixels, 84 partial viewport packets, 1534 retained rows, zero
full retries, and `black_return=false`. On the real 169x159 Codex grid, cumulative retained-render
time fell from about 33961 us to 2060 us (about 16.5x), deadline jank fell from about 21.96% to
2.54%, P95 fell from 85 ms to 13 ms, and direct-finger tracking error remained 0 px. The subsequent
direction-aware horizontal transaction changes are locally compiled and unit-tested; ADB is
currently unavailable, so their `page-transition-v2` device evidence remains the next verification
gate. Screenshots are not accepted as performance or correctness evidence.
