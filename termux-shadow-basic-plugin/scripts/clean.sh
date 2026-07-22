#!/data/data/com.termux/files/usr/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
rm -rf "$ROOT_DIR/.gradle" "$ROOT_DIR/build" "$ROOT_DIR/dist" "$ROOT_DIR/plugin-app/build"
