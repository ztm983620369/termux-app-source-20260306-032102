#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TARGET=aarch64-linux-android
API=${ANDROID_API_LEVEL:-23}

if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    NDK=$ANDROID_NDK_HOME
else
    SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}
    NDK=
    for candidate in "$SDK"/ndk/*; do
        [ -x "$candidate/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${API}-clang" ] \
            || continue
        NDK=$candidate
    done
fi

[ -n "$NDK" ] || {
    printf 'Android NDK not found; set ANDROID_NDK_HOME or ANDROID_HOME\n' >&2
    exit 1
}

LINKER=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${API}-clang
STRIP=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip
[ -x "$LINKER" ] || {
    printf 'Android linker not found: %s\n' "$LINKER" >&2
    exit 1
}

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$LINKER
cd "$ROOT"
cargo test --locked
cargo build --locked --release --target "$TARGET"

mkdir -p "$ROOT/dist"
cp "$ROOT/target/$TARGET/release/shadow-plugin" "$ROOT/dist/shadow-plugin"
"$STRIP" "$ROOT/dist/shadow-plugin"
chmod 755 "$ROOT/dist/shadow-plugin"

file "$ROOT/dist/shadow-plugin"
sha256sum "$ROOT/dist/shadow-plugin"
