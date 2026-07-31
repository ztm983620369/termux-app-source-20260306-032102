#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly CHECKPOINT="/root/termux-render-checkpoints/termux-before-pinch-live-edge-policy-20260727.tar.gz"
readonly CHECKPOINT_SHA256="d83396a48e2bb7a9951350fc30588d3ea1acdbe1778e42f2e767eeac89b5b701"
readonly BACKUP_DIR="/root/termux-render-checkpoints/pinch-live-edge-policy-undo"
readonly LATEST_FILE="${BACKUP_DIR}/latest"
readonly APK_RELATIVE="app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk"

readonly -a TRACKED_PATHS=(
  "terminal-view/src/main/java/com/termux/view/TerminalView.java"
  "terminal-view/src/main/java/com/termux/view/TerminalPinchViewportAnchor.java"
  "terminal-view/src/test/java/com/termux/view/TerminalPinchViewportAnchorTest.java"
  "app/src/androidTest/java/com/termux/terminal/TerminalIndustrialInstrumentation.java"
)

SOURCE_ONLY=false
MODE=rollback
UNDO_ARCHIVE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-only)
      SOURCE_ONLY=true
      shift
      ;;
    --undo)
      MODE=undo
      shift
      if [[ $# -gt 0 && "$1" != --* ]]; then
        UNDO_ARCHIVE="$1"
        shift
      fi
      ;;
    -h|--help)
      echo "Usage: $0 [--source-only] | --undo [backup.tar.gz] [--source-only]"
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

build_and_install() {
  [[ "${SOURCE_ONLY}" == "true" ]] && return
  "${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" --no-daemon :app:assembleDebug
  local apk="${ROOT_DIR}/${APK_RELATIVE}"
  [[ -f "${apk}" ]] || { echo "Rollback APK missing: ${apk}" >&2; exit 1; }
  if ! command -v adb >/dev/null 2>&1; then
    echo "Rollback built; adb unavailable. APK: ${apk}"
    return
  fi
  mapfile -t devices < <(adb devices | awk 'NR > 1 && $2 == "device" {print $1}')
  if [[ ${#devices[@]} -eq 1 ]]; then
    adb -s "${devices[0]}" install --no-streaming -r -t "${apk}"
    echo "Rollback built and installed on ${devices[0]}"
  else
    echo "Rollback built; expected one adb device, found ${#devices[@]}. APK: ${apk}"
  fi
}

mkdir -p "${BACKUP_DIR}"
if [[ "${MODE}" == "undo" ]]; then
  if [[ -z "${UNDO_ARCHIVE}" ]]; then
    [[ -s "${LATEST_FILE}" ]] || { echo "No optimized-source backup recorded" >&2; exit 1; }
    UNDO_ARCHIVE="$(<"${LATEST_FILE}")"
  fi
  [[ -f "${UNDO_ARCHIVE}" ]] || { echo "Undo archive missing: ${UNDO_ARCHIVE}" >&2; exit 1; }
  tar -xzf "${UNDO_ARCHIVE}" -C "${ROOT_DIR}"
  echo "Restored optimized live-edge sources from ${UNDO_ARCHIVE}"
  build_and_install
  exit 0
fi

[[ -f "${CHECKPOINT}" ]] || { echo "Checkpoint missing: ${CHECKPOINT}" >&2; exit 1; }
actual_sha256="$(sha256sum "${CHECKPOINT}" | awk '{print $1}')"
[[ "${actual_sha256}" == "${CHECKPOINT_SHA256}" ]] || {
  echo "Checkpoint hash mismatch: expected ${CHECKPOINT_SHA256}, got ${actual_sha256}" >&2
  exit 1
}

timestamp="$(date +%Y%m%d-%H%M%S)"
backup="${BACKUP_DIR}/optimized-before-rollback-${timestamp}.tar.gz"
tar -czf "${backup}" -C "${ROOT_DIR}" "${TRACKED_PATHS[@]}"
printf '%s\n' "${backup}" > "${LATEST_FILE}"
tar -xzf "${CHECKPOINT}" -C "${ROOT_DIR}" "${TRACKED_PATHS[@]}"

echo "Restored pre-live-edge checkpoint ${CHECKPOINT_SHA256}"
echo "Optimized-source undo backup: ${backup}"
build_and_install
