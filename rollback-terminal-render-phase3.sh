#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly WORKSPACE_ROOT="$(dirname "${ROOT_DIR}")"
readonly DEFAULT_ARCHIVE="/root/.codex/checkpoints/termux-phase2-before-phase3-20260726.tar.gz"
readonly EXPECTED_SHA256="7024d194ff591298e18492747e6ece427ae293d92fb6bb5ecbb9a43c6aa94eea"
readonly BACKUP_DIR="${XDG_STATE_HOME:-${HOME}/.local/state}/termux-render-phase3-backups"
readonly LATEST_FILE="${BACKUP_DIR}/latest"
readonly APK_RELATIVE="app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk"

readonly -a PHASE3_ONLY_FILES=(
  "terminal-emulator/src/test/java/com/termux/terminal/TerminalCompatibilityCheckpointTest.java"
  "terminal-view/src/main/java/com/termux/view/TerminalRenderDamageTracker.java"
  "terminal-view/src/test/java/com/termux/view/TerminalRenderDamageTrackerTest.java"
  "terminal-view/src/test/java/com/termux/view/GhosttyRenderNodeRendererTest.java"
  "docs/terminal-render-phase3-20260726.md"
)

usage() {
  cat <<'EOF'
Usage:
  ./rollback-terminal-render-phase3.sh [--source-only]
  ./rollback-terminal-render-phase3.sh --undo [backup.tar.gz] [--source-only]

Restores the exact pre-phase-3 terminal emulator/view, device instrumentation,
and industrial-lab tools from the verified phase-2 checkpoint. Before restoring,
the current phase-3 files are archived so --undo can put them back.
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
  "${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" :app:assembleDebug
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
    [[ -s "${LATEST_FILE}" ]] || { echo "No phase-3 undo backup recorded" >&2; exit 1; }
    UNDO_ARCHIVE="$(<"${LATEST_FILE}")"
  fi
  [[ -f "${UNDO_ARCHIVE}" ]] || { echo "Undo archive missing: ${UNDO_ARCHIVE}" >&2; exit 1; }
  tar -xzf "${UNDO_ARCHIVE}" -C "${WORKSPACE_ROOT}"
  echo "Restored phase-3 sources from ${UNDO_ARCHIVE}"
  build_and_install
  exit 0
fi

[[ -f "${DEFAULT_ARCHIVE}" ]] || { echo "Checkpoint missing: ${DEFAULT_ARCHIVE}" >&2; exit 1; }
actual_sha256="$(sha256sum "${DEFAULT_ARCHIVE}" | awk '{print $1}')"
[[ "${actual_sha256}" == "${EXPECTED_SHA256}" ]] || {
  echo "Checkpoint hash mismatch: expected ${EXPECTED_SHA256}, got ${actual_sha256}" >&2
  exit 1
}

timestamp="$(date +%Y%m%d-%H%M%S)"
backup="${BACKUP_DIR}/phase3-before-rollback-${timestamp}.tar.gz"
tar -czf "${backup}" -C "${WORKSPACE_ROOT}" \
  "$(basename "${ROOT_DIR}")/terminal-emulator/src" \
  "$(basename "${ROOT_DIR}")/terminal-emulator/build.gradle" \
  "$(basename "${ROOT_DIR}")/terminal-view/src" \
  "$(basename "${ROOT_DIR}")/terminal-view/build.gradle" \
  "$(basename "${ROOT_DIR}")/app/src/androidTest/java/com/termux/terminal" \
  "$(basename "${ROOT_DIR}")/docs" \
  "termux-tui-lab/tools"
printf '%s\n' "${backup}" > "${LATEST_FILE}"

for relative in "${PHASE3_ONLY_FILES[@]}"; do
  rm -f "${ROOT_DIR}/${relative}"
done
tar -xzf "${DEFAULT_ARCHIVE}" -C "${WORKSPACE_ROOT}"

echo "Restored verified pre-phase-3 checkpoint ${EXPECTED_SHA256}"
echo "Phase-3 undo backup: ${backup}"
build_and_install
