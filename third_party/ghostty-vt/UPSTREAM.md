# Ghostty VT upstream

## Source and license

- Repository: https://github.com/ghostty-org/ghostty
- Pinned commit: `15484b607eb5a518dedf1548247c923b8abaae7c`
- Commit date: 2026-07-23
- Library version reported by the C API: `0.1.0-dev`
- License: MIT; see `LICENSE` in this directory.
- Local research checkout: `/root/ghostty-upstream-20260724` (not an app build input).

The emitted VT library also statically contains uucode 0.2.0, simdutf 5.2.8, Highway 1.2.0,
and generated Unicode data. Their exact redistribution notices are packaged in the APK at
`assets/ghostty-vt/THIRD_PARTY_NOTICES.txt` from this directory's `assets` source tree.

The pin is intentional. Ghostty describes the `libghostty-vt` functionality as available while its
C API signatures are still allowed to evolve. Termux therefore exposes only a small private JNI
contract and never compiles Java code against an unpinned upstream ABI.

## Vendored Android artifacts

This directory contains the complete installed C headers from the pinned build. The corresponding
stripped `libghostty-vt.so` files live under
`terminal-emulator/src/main/jniLibs/<abi>/` and are packaged for every supported Termux ABI:

```text
arm64-v8a    544a8e0cd751912e98e7c50870fb561cf249b5087f1f449c48c611ea30c02f33
armeabi-v7a  7c1d4bce0f8003c00b14be7f6080ff70922ecedf23a8ae1a6625ca1eff3aa7bd
x86          c0fa3ed0fe01a455f754836f341d1a6e01c64836a222160ae2adbce79588637f
x86_64       6dd3ab77dabd74e90e529533fe77b93462c9b777b7b329fbaeaecb807287ce09
```

`BINARIES.sha256` is the machine-readable manifest. All four libraries are Android API 23,
`ReleaseFast`, SIMD-enabled builds with `0x4000` ELF LOAD alignment. The final universal APK was
also checked with `zipalign -c -P 16 -v 4`, and its four packaged library hashes match this manifest.

Rebuild from the exact source pin with Zig 0.16 and Android NDK r29:

```sh
GHOSTTY_SOURCE_DIR=/root/ghostty-upstream-20260724 \
ZIG_BIN=/root/.local/opt/zig-x86_64-linux-0.16.0/zig \
ANDROID_NDK_HOME=/opt/android-sdk/ndk/29.0.14206865 \
third_party/ghostty-vt/build-android.sh
```

The script refuses a different commit, validates the required full-terminal/render symbols,
strips each library, requires 16 KiB LOAD alignment, installs the headers, and regenerates the hash
manifest.

## Termux integration boundary

`ghostty_terminal_backend.c` dynamically loads the packaged library and resolves the pinned C ABI.
One Termux session handle owns a complete `GhosttyTerminal`, `GhosttyRenderState`, reusable row and
cell iterators, input encoders, effect callbacks, and grapheme storage. Ghostty is the normal-session
semantic authority, not a differential sidecar:

- each original PTY chunk is parsed exactly once by `ghostty_terminal_vt_write` on its session's
  dedicated `TermSessionInputReader` thread; neither the Java VT parser nor Android main thread
  executes the healthy parse hot path;
- write-PTY replies, bell, title, OSC 52 clipboard, palette changes, device attributes, XTVERSION,
  size reports, and Termux OSC 8900 host control are bridged synchronously;
- cell/pixel resize, primary/alternate screen, scrollback, cursor, modes, default palette, dynamic
  colors, synchronized output, and configured cursor style are mirrored through one cached state
  packet per mutation;
- key press/repeat/release (including Kitty keyboard physical keys), Unicode text, mouse, focus, and
  bracketed paste use Ghostty's own encoders;
- selection formatting, word expansion, wide-grapheme snapping, OSC 8 targets, URL context, and
  transcript export read Ghostty directly; and
- the active viewport renderer consumes retained native delta packets and redraws only changed rows.

Render-state extraction uses the pinned bulk C API: terminal and frame metadata are grouped through
`ghostty_terminal_get_multi` and `ghostty_render_state_get_multi`, colors use the sized
`ghostty_render_state_colors_get` structure, and dirty-row/cell fields use the corresponding row
multi-getters. Graphemes are encoded by `libghostty-vt` directly into the JNI direct buffer through
`GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8`; the bridge no longer materializes a temporary
UTF-32 array or re-encodes every cell. Width and content metadata use `ghostty_cell_get_multi`.
Resolved foreground/background values are queried only when the Ghostty style or content tag says
that an override exists; default-color cells avoid both optional calls. Those resolved colors remain
individual queries because their valid `NO_VALUE` result would intentionally terminate a multi-get
request.

The 24-byte cell records carry ARGB foreground/background/underline colors, style flags, wide-cell
state, and an offset/length into the packet's UTF-8 grapheme arena. `libghostty-vt` owns parsing,
terminal state, grapheme semantics, styling, cursor, scrollback, and selection. The application
frontend intentionally uses the upstream Termux zoom and scrolling interaction path.

`ghostty_vt_scan.c` retains the earlier Ghostty-derived NEON/SIMD coarse scanner for the dormant
Java compatibility parser and its differential tests. It is not invoked by a healthy
Ghostty-authoritative PTY session.

The pinned implementation interprets `GhosttyTerminalOptions.max_scrollback` as a byte budget even
though this pinned C header describes lines. The JNI layer therefore converts Termux's logical row
limit to a conservative, lazily allocated byte budget capped at 512 MiB, and clamps UI-visible
history back to Termux's configured logical row contract.

## Fail-safe and rollback

The backend is enabled by default and loaded with `dlopen`. A missing ABI, missing pinned symbol,
initialization failure, native mutation failure, or explicit disable fails closed to Termux's Java
implementation.

Every Ghostty-authoritative session writes an ordered PTY/resize/reset/theme compatibility journal
through a 256 KiB buffered temporary file. Java state is not live and the healthy stream is parsed
only once. The journal is sealed and replayed by a dedicated worker only for explicit fallback or at
the 256 MiB safety boundary, so an already-running session can still restore its transcript, cursor,
modes, colors, and geometry before the native handle is closed.

After restoration, later PTY chunks are routed through a bounded fallback queue to the historical
main-thread Java parser. Native effects originating on the worker are marshalled to Android main;
PTY replies remain on the ordered output queue. Process exit waits for reader EOF before appending
the exit marker, preventing tail-byte loss or reordering.

Immediate in-session/process rollback is one terminal command:

```sh
printf '\033]8900;terminal-backend;java\033\\'
```

For a persistent source/build default, run `third_party/ghostty-vt/terminal-backend java`; restore
the Ghostty default with `third_party/ghostty-vt/terminal-backend ghostty`. This changes only the
single production gate line and never resets or overwrites unrelated worktree changes.

The render-query optimization has an independent compile-time rollback. Run
`third_party/ghostty-vt/render-query scalar` to restore the prior per-field cell extraction, or
`third_party/ghostty-vt/render-query batch` to re-enable grouped state reads and Ghostty-direct
UTF-8. Both branches retain the same JNI packet ABI and are built in CI/local verification so a
rollback does not require reverting unrelated parser or renderer work.
