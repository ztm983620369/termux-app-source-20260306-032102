#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly ARCHIVE="/root/termux-render-checkpoints/termux-pinch-viewport-pretracked-anchor-20260727.tar.gz"
readonly EXPECTED_SHA256="fb00d0c885ff3138b2ffa599fd6d8ebb0f6af0d4d42676a5f90e89ada71117ee"
readonly BACKUP_DIR="/root/termux-render-checkpoints/pinch-viewport-anchor-undo"
readonly LATEST_FILE="${BACKUP_DIR}/latest"
readonly APK_RELATIVE="app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk"

readonly -a TRACKED_PATHS=(
  "terminal-view/src/main/java/com/termux/view/TerminalView.java"
  "terminal-view/src/main/java/com/termux/view/GestureAndScaleRecognizer.java"
  "terminal-view/src/main/java/com/termux/view/TerminalViewportPosition.java"
  "terminal-view/src/main/java/com/termux/view/TerminalPinchViewportAnchor.java"
  "terminal-view/src/test/java/com/termux/view/TerminalViewportPositionTest.java"
  "terminal-view/src/test/java/com/termux/view/TerminalPinchViewportAnchorTest.java"
  "terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalBackend.java"
  "terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java"
  "terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java"
  "terminal-emulator/src/main/jni/ghostty_terminal_backend.c"
  "app/src/androidTest/java/com/termux/terminal/TerminalIndustrialInstrumentation.java"
)

readonly -a NEW_PATHS=(
  "terminal-view/src/main/java/com/termux/view/TerminalPinchViewportAnchor.java"
  "terminal-view/src/test/java/com/termux/view/TerminalPinchViewportAnchorTest.java"
)

usage() {
  cat <<'EOF'
Usage:
  ./rollback-terminal-pinch-viewport-anchor.sh [--source-only]
  ./rollback-terminal-pinch-viewport-anchor.sh --undo [backup.tar.gz] [--source-only]

Restore the terminal sources from immediately before tracked pinch viewport anchoring.
The current optimized sources are archived first; --undo restores that archived version.
EOF
}

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
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
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
  if [[ "${#devices[@]}" -eq 1 ]]; then
    adb -s "${devices[0]}" install --no-streaming -r -t "${apk}"
    echo "Rollback built and installed on ${devices[0]}"
  else
    echo "Rollback built; expected one adb device, found ${#devices[@]}. APK: ${apk}"
  fi
}

archive_existing_paths() {
  local destination="$1"
  local -a existing=()
  local path
  for path in "${TRACKED_PATHS[@]}"; do
    [[ -e "${ROOT_DIR}/${path}" ]] && existing+=("${path}")
  done
  [[ "${#existing[@]}" -gt 0 ]] || { echo "No tracked sources found" >&2; exit 1; }
  tar -czf "${destination}" -C "${ROOT_DIR}" "${existing[@]}"
}

mkdir -p "${BACKUP_DIR}"
if [[ "${MODE}" == "undo" ]]; then
  if [[ -z "${UNDO_ARCHIVE}" ]]; then
    [[ -s "${LATEST_FILE}" ]] || { echo "No optimized-source backup recorded" >&2; exit 1; }
    UNDO_ARCHIVE="$(<"${LATEST_FILE}")"
  fi
  [[ -f "${UNDO_ARCHIVE}" ]] || { echo "Undo archive missing: ${UNDO_ARCHIVE}" >&2; exit 1; }
  tar -xzf "${UNDO_ARCHIVE}" -C "${ROOT_DIR}"
  echo "Restored tracked-anchor sources from ${UNDO_ARCHIVE}"
  build_and_install
  exit 0
fi

[[ -f "${ARCHIVE}" ]] || { echo "Checkpoint missing: ${ARCHIVE}" >&2; exit 1; }
actual_sha256="$(sha256sum "${ARCHIVE}" | awk '{print $1}')"
[[ "${actual_sha256}" == "${EXPECTED_SHA256}" ]] || {
  echo "Checkpoint hash mismatch: expected ${EXPECTED_SHA256}, got ${actual_sha256}" >&2
  exit 1
}

timestamp="$(date +%Y%m%d-%H%M%S)"
backup="${BACKUP_DIR}/optimized-before-rollback-${timestamp}.tar.gz"
archive_existing_paths "${backup}"
printf '%s\n' "${backup}" > "${LATEST_FILE}"

for path in "${NEW_PATHS[@]}"; do
  rm -f "${ROOT_DIR}/${path}"
done
tar -xzf "${ARCHIVE}" -C "${ROOT_DIR}"

echo "Restored pre-anchor checkpoint ${EXPECTED_SHA256}"
echo "Optimized-source undo backup: ${backup}"
build_and_install
