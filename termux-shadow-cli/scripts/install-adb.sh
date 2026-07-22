#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
HOST_REPO=$(CDPATH= cd -- "$ROOT/.." && pwd)
TEMPLATE=$HOST_REPO/termux-shadow-basic-plugin
BINARY=$ROOT/dist/shadow-plugin

if [ -z "${SERIAL:-}" ]; then
    SERIALS=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    SERIAL_COUNT=$(printf '%s\n' "$SERIALS" | awk 'NF { count++ } END { print count + 0 }')
    [ "$SERIAL_COUNT" -eq 1 ] || {
        printf 'Expected exactly one adb device, found %s; set SERIAL explicitly.\n' \
            "$SERIAL_COUNT" >&2
        exit 1
    }
    SERIAL=$SERIALS
fi

[ -x "$BINARY" ] || {
    printf 'Native binary not found: %s (run ./scripts/build-android.sh)\n' "$BINARY" >&2
    exit 1
}
[ -f "$TEMPLATE/shadow-plugin.properties" ] || {
    printf 'Canonical template not found: %s\n' "$TEMPLATE" >&2
    exit 1
}

adb -s "$SERIAL" get-state >/dev/null
adb -s "$SERIAL" shell run-as com.termux mkdir -p \
    files/usr/bin files/usr/share/termux-shadow-plugin files/home

TEMPLATE_ARCHIVE=$(mktemp)
REMOTE_BINARY=/data/local/tmp/termux-shadow-plugin-bin-$$
REMOTE_TEMPLATE=/data/local/tmp/termux-shadow-template-$$.tar
cleanup() {
    rm -f "$TEMPLATE_ARCHIVE"
    adb -s "$SERIAL" shell rm -f "$REMOTE_BINARY" "$REMOTE_TEMPLATE" \
        >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM

# adb exec-in is transport-dependent for binary tar streams. Push complete files, compare hashes,
# then let the app UID copy/extract them from the temporary boundary.
adb -s "$SERIAL" push "$BINARY" "$REMOTE_BINARY" >/dev/null
adb -s "$SERIAL" shell chmod 644 "$REMOTE_BINARY"
LOCAL_BINARY_SHA=$(sha256sum "$BINARY" | awk '{print $1}')
REMOTE_BINARY_SHA=$(adb -s "$SERIAL" shell sha256sum "$REMOTE_BINARY" | awk '{print $1}')
[ "$LOCAL_BINARY_SHA" = "$REMOTE_BINARY_SHA" ] || {
    printf 'Native binary transfer hash mismatch\n' >&2
    exit 1
}

# Replace the executable by inode so an in-flight invocation never observes a truncated ELF.
adb -s "$SERIAL" shell run-as com.termux /system/bin/cp \
    "$REMOTE_BINARY" files/usr/bin/.shadow-plugin.new
adb -s "$SERIAL" shell run-as com.termux chmod 755 files/usr/bin/.shadow-plugin.new
adb -s "$SERIAL" shell \
    "run-as com.termux /system/bin/mv files/usr/bin/.shadow-plugin.new files/usr/bin/shadow-plugin"

tar -C "$TEMPLATE" \
    --exclude='./.gradle' \
    --exclude='./build' \
    --exclude='./dist' \
    --exclude='./local.properties' \
    --exclude='./plugin-app/build' \
    -cf "$TEMPLATE_ARCHIVE" .
adb -s "$SERIAL" push "$TEMPLATE_ARCHIVE" "$REMOTE_TEMPLATE" >/dev/null
adb -s "$SERIAL" shell chmod 644 "$REMOTE_TEMPLATE"
LOCAL_TEMPLATE_SHA=$(sha256sum "$TEMPLATE_ARCHIVE" | awk '{print $1}')
REMOTE_TEMPLATE_SHA=$(adb -s "$SERIAL" shell sha256sum "$REMOTE_TEMPLATE" | awk '{print $1}')
[ "$LOCAL_TEMPLATE_SHA" = "$REMOTE_TEMPLATE_SHA" ] || {
    printf 'Template transfer hash mismatch\n' >&2
    exit 1
}

STAGING=files/usr/share/termux-shadow-plugin/.template.new
adb -s "$SERIAL" shell run-as com.termux rm -rf "$STAGING"
adb -s "$SERIAL" shell run-as com.termux mkdir -p "$STAGING"
adb -s "$SERIAL" shell run-as com.termux /system/bin/tar \
    -C "$STAGING" -xf "$REMOTE_TEMPLATE"
adb -s "$SERIAL" shell run-as com.termux test -f "$STAGING/build.gradle"
adb -s "$SERIAL" shell run-as com.termux test -f "$STAGING/plugin-app/build.gradle"
adb -s "$SERIAL" shell run-as com.termux test -f \
    "$STAGING/shadow/loader/sample-loader-debug.apk"
adb -s "$SERIAL" shell run-as com.termux test -f \
    "$STAGING/shadow/runtime/sample-runtime-debug.apk"
adb -s "$SERIAL" shell \
    "run-as com.termux /system/bin/sh -c 'rm -rf files/usr/share/termux-shadow-plugin/template.old; if [ -d files/usr/share/termux-shadow-plugin/template ]; then mv files/usr/share/termux-shadow-plugin/template files/usr/share/termux-shadow-plugin/template.old; fi; mv $STAGING files/usr/share/termux-shadow-plugin/template; rm -rf files/usr/share/termux-shadow-plugin/template.old'"

if adb -s "$SERIAL" shell run-as com.termux test -e \
        files/home/termux-shadow-basic-plugin/shadow-plugin.properties; then
    printf 'Preserved existing device workspace: ~/termux-shadow-basic-plugin\n'
    printf 'Run `shadow-plugin sync` inside it to update tooling without touching business code.\n'
else
    adb -s "$SERIAL" shell run-as com.termux mkdir -p files/home/termux-shadow-basic-plugin
    adb -s "$SERIAL" shell run-as com.termux /system/bin/tar \
        -C files/home/termux-shadow-basic-plugin -xf "$REMOTE_TEMPLATE"
    printf 'Installed standard workspace: ~/termux-shadow-basic-plugin\n'
fi

if [ -n "${HOST_APK:-}" ]; then
    adb -s "$SERIAL" install -r "$HOST_APK"
    adb -s "$SERIAL" shell am start -W -n com.termux/.app.TermuxActivity >/dev/null
fi

adb -s "$SERIAL" shell \
    "run-as com.termux /system/bin/env HOME=/data/data/com.termux/files/home TERMUX_HOME=/data/data/com.termux/files/home PREFIX=/data/data/com.termux/files/usr /data/data/com.termux/files/usr/bin/shadow-plugin --version"
