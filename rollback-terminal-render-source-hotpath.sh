#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly WORKSPACE_ROOT="$(dirname "${ROOT_DIR}")"
readonly ARCHIVE="/root/termux-render-checkpoints/termux-phase3-before-source-hotpath-20260726.tar.gz"
readonly EXPECTED_SHA256="c69217593a2bcf7019b596e79385a7198e5099b2ac298c85a1c3a30f675bc29b"
readonly BACKUP_DIR="/root/termux-render-checkpoints/source-hotpath-undo"
readonly LATEST_FILE="${BACKUP_DIR}/latest"
readonly APK_RELATIVE="app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk"

readonly -a TRACKED_PATHS=(
  "termux-app-source-20260306-032102/terminal-emulator/src/main/java/com/termux/terminal/GhosttyRenderDelta.java"
  "termux-app-source-20260306-032102/terminal-emulator/src/main/jni/ghostty_terminal_backend.c"
  "termux-app-source-20260306-032102/terminal-emulator/src/test/java/com/termux/terminal/GhosttyRenderDeltaTest.java"
  "termux-app-source-20260306-032102/terminal-view/src/main/java/com/termux/view/GhosttyRenderNodeRenderer.java"
  "termux-app-source-20260306-032102/terminal-view/src/main/java/com/termux/view/TerminalView.java"
  "termux-app-source-20260306-032102/terminal-view/src/test/java/com/termux/view/GhosttyRenderNodeRendererTest.java"
  "termux-tui-lab/tools/analyze_termux_industrial.py"
  "termux-tui-lab/tools/test_analyze_termux_industrial.py"
)

usage() {
  cat <<'EOF'
Usage:
  ./rollback-terminal-render-source-hotpath.sh [--source-only]
  ./rollback-terminal-render-source-hotpath.sh --undo [backup.tar.gz] [--source-only]

Restores the exact source state from immediately before the phase-3 small-font/render
hot-path optimization. The current files are archived first, so --undo can restore them.
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
  if [[ "${SOURCE_ONLY}" == "true" ]]; then
    return
  fi
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

mkdir -p "${BACKUP_DIR}"
if [[ "${MODE}" == "undo" ]]; then
  if [[ -z "${UNDO_ARCHIVE}" ]]; then
    [[ -s "${LATEST_FILE}" ]] || { echo "No source-hotpath undo backup recorded" >&2; exit 1; }
    UNDO_ARCHIVE="$(<"${LATEST_FILE}")"
  fi
  [[ -f "${UNDO_ARCHIVE}" ]] || { echo "Undo archive missing: ${UNDO_ARCHIVE}" >&2; exit 1; }
  tar -xzf "${UNDO_ARCHIVE}" -C "${WORKSPACE_ROOT}"
  echo "Restored optimized source from ${UNDO_ARCHIVE}"
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
tar -czf "${backup}" -C "${WORKSPACE_ROOT}" "${TRACKED_PATHS[@]}"
printf '%s\n' "${backup}" > "${LATEST_FILE}"
tar -xzf "${ARCHIVE}" -C "${WORKSPACE_ROOT}"

echo "Restored pre-hotpath checkpoint ${EXPECTED_SHA256}"
echo "Optimized-source undo backup: ${backup}"
build_and_install
