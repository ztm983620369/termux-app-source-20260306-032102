#!/usr/bin/env bash
set -euo pipefail

EXPECTED_COMMIT="15484b607eb5a518dedf1548247c923b8abaae7c"
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
GHOSTTY_SOURCE_DIR="${GHOSTTY_SOURCE_DIR:-}"
ZIG_BIN="${ZIG_BIN:-$(command -v zig 2>/dev/null || true)}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"

if [[ -z "$GHOSTTY_SOURCE_DIR" || ! -d "$GHOSTTY_SOURCE_DIR/.git" ]]; then
    echo "GHOSTTY_SOURCE_DIR must name the pinned Ghostty git checkout" >&2
    exit 2
fi
if [[ "$(git -C "$GHOSTTY_SOURCE_DIR" rev-parse HEAD)" != "$EXPECTED_COMMIT" ]]; then
    echo "Ghostty checkout is not pinned to $EXPECTED_COMMIT" >&2
    exit 2
fi
if [[ -z "$ZIG_BIN" || ! -x "$ZIG_BIN" ]]; then
    echo "ZIG_BIN must name a Zig 0.16 executable" >&2
    exit 2
fi
if [[ "$("$ZIG_BIN" version)" != 0.16.0* ]]; then
    echo "Ghostty Android prebuilts require Zig 0.16.x" >&2
    exit 2
fi
if [[ -z "$ANDROID_NDK_HOME" || ! -d "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" ]]; then
    echo "ANDROID_NDK_HOME must name an Android NDK installation" >&2
    exit 2
fi

LLVM_PREBUILT="$(find "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d -print -quit)"
LLVM_STRIP="$LLVM_PREBUILT/bin/llvm-strip"
LLVM_NM="$LLVM_PREBUILT/bin/llvm-nm"
LLVM_READELF="$LLVM_PREBUILT/bin/llvm-readelf"
ANDROID_SDK_ROOT_EFFECTIVE="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$(cd "$ANDROID_NDK_HOME/../.." && pwd)}}"
for tool in "$LLVM_STRIP" "$LLVM_NM" "$LLVM_READELF"; do
    if [[ ! -x "$tool" ]]; then
        echo "Missing NDK tool: $tool" >&2
        exit 2
    fi
done

if [[ $# -eq 0 ]]; then
    set -- arm64-v8a armeabi-v7a x86 x86_64
fi

for abi in "$@"; do
    case "$abi" in
        arm64-v8a) target="aarch64-linux-android.23" ;;
        armeabi-v7a) target="arm-linux-androideabi.23" ;;
        x86) target="x86-linux-android.23" ;;
        x86_64) target="x86_64-linux-android.23" ;;
        *) echo "Unsupported Android ABI: $abi" >&2; exit 2 ;;
    esac

    prefix="$ROOT_DIR/terminal-emulator/build/ghostty-vt/$abi"
    destination="$ROOT_DIR/terminal-emulator/src/main/jniLibs/$abi/libghostty-vt.so"
    mkdir -p "$prefix" "$(dirname "$destination")"

    (
        cd "$GHOSTTY_SOURCE_DIR"
        PATH="$(dirname "$ZIG_BIN"):$PATH" \
        ANDROID_HOME="$ANDROID_SDK_ROOT_EFFECTIVE" \
        ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT_EFFECTIVE" \
        ANDROID_NDK_HOME="$ANDROID_NDK_HOME" \
        "$ZIG_BIN" build \
            -Demit-lib-vt \
            -Demit-exe=false \
            -Demit-docs=false \
            -Demit-themes=false \
            -Demit-terminfo=false \
            -Demit-termcap=false \
            -Demit-macos-app=false \
            -Demit-xcframework=false \
            -Dapp-runtime=none \
            -Dsentry=false \
            -Di18n=false \
            -Dgtk-x11=false \
            -Dgtk-wayland=false \
            -Dtarget="$target" \
            -Doptimize=ReleaseFast \
            --prefix "$prefix"
    )

    source_library="$prefix/lib/libghostty-vt.so.0.1.0"
    if [[ ! -f "$source_library" ]]; then
        echo "Ghostty build did not produce $source_library" >&2
        exit 1
    fi
    cp "$source_library" "$destination"
    "$LLVM_STRIP" --strip-all "$destination"

    symbols="$($LLVM_NM -D --defined-only "$destination")"
    while IFS= read -r required_symbol; do
        if [[ "$symbols" != *" $required_symbol"* ]]; then
            echo "$abi libghostty-vt is missing required JNI symbol: $required_symbol" >&2
            exit 1
        fi
    done < <(awk '
        /^[[:space:]]*RESOLVE_API\(/ {
            line = $0
            while (line !~ /"/ && getline > 0) line = line $0
            split(line, fields, "\"")
            if (fields[2] != "") print fields[2]
        }
    ' "$ROOT_DIR/terminal-emulator/src/main/jni/ghostty_terminal_backend.c")
    alignments="$($LLVM_READELF -lW "$destination" | awk '/ LOAD / {print $NF}' | sort -u)"
    if [[ "$alignments" != "0x4000" ]]; then
        echo "$abi has unsupported LOAD alignment(s): $alignments" >&2
        exit 1
    fi

    mkdir -p "$ROOT_DIR/third_party/ghostty-vt/include"
    cp -R "$prefix/include/." "$ROOT_DIR/third_party/ghostty-vt/include/"
    echo "Built $abi ($target): $(sha256sum "$destination" | awk '{print $1}')"
done

(
    cd "$ROOT_DIR/terminal-emulator/src/main/jniLibs"
    find . -mindepth 2 -maxdepth 2 -name 'libghostty-vt.so' -type f -print0 \
        | sort -z \
        | xargs -0 sha256sum
) > "$ROOT_DIR/third_party/ghostty-vt/BINARIES.sha256"
