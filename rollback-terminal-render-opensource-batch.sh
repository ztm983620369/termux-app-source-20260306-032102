#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly WORKSPACE_ROOT="$(dirname "${ROOT_DIR}")"
readonly BATCH_ARCHIVE="/root/termux-render-checkpoints/termux-before-opensource-glyph-batching-20260726.tar.gz"
readonly BATCH_SHA256="ac74e6f9dae0859581d74a01f81b3b804df4147efbdcec903cf864b929253fe1"
readonly WARMUP_ARCHIVE="/root/termux-render-checkpoints/termux-before-idle-glyph-warmup-20260726.tar.gz"
readonly WARMUP_SHA256="6c11a15d30af81fe52fb2cda5df419bdac731af9e3019ff991af6dbec44a2f2e"
readonly PROBE_ARCHIVE="/root/termux-render-checkpoints/termux-before-opensource-glyph-probe-20260726.tar.gz"
readonly PROBE_SHA256="aa360bd8466798dadadfd9ddb2f1d44981304b6cd5332b5d0cb0b96eb82f6827"
readonly BACKUP_DIR="/root/termux-render-checkpoints/opensource-batch-undo"
readonly LATEST_FILE="${BACKUP_DIR}/latest"
readonly APK_RELATIVE="app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk"

readonly -a TRACKED_PATHS=(
  "terminal-view/src/main/java/com/termux/view/GhosttyRenderNodeRenderer.java"
  "terminal-view/src/main/java/com/termux/view/TerminalRenderer.java"
  "terminal-view/src/main/java/com/termux/view/TerminalView.java"
  "terminal-view/src/debug/java/com/termux/view/GhosttyViewportRenderProbe.java"
  "terminal-view/src/test/java/com/termux/view/GhosttyRenderNodeRendererTest.java"
)

usage() {
  cat <<'EOF'
Usage:
  ./rollback-terminal-render-opensource-batch.sh [--source-only]
  ./rollback-terminal-render-opensource-batch.sh --undo [backup.tar.gz] [--source-only]

Restore the exact terminal rendering sources from immediately before the ordered glyph
batching, glyph-shape cache, and idle warmup pass. The optimized sources are archived
first. Use --undo to restore the most recent optimized-source backup.
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

verify_archive() {
  local archive="$1"
  local expected="$2"
  [[ -f "${archive}" ]] || { echo "Checkpoint missing: ${archive}" >&2; exit 1; }
  local actual
  actual="$(sha256sum "${archive}" | awk '{print $1}')"
  [[ "${actual}" == "${expected}" ]] || {
    echo "Checkpoint hash mismatch: ${archive}" >&2
    echo "Expected ${expected}, got ${actual}" >&2
    exit 1
  }
}

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
    [[ -s "${LATEST_FILE}" ]] || { echo "No optimized-source backup recorded" >&2; exit 1; }
    UNDO_ARCHIVE="$(<"${LATEST_FILE}")"
  fi
  [[ -f "${UNDO_ARCHIVE}" ]] || { echo "Undo archive missing: ${UNDO_ARCHIVE}" >&2; exit 1; }
  tar -xzf "${UNDO_ARCHIVE}" -C "${ROOT_DIR}"
  echo "Restored optimized sources from ${UNDO_ARCHIVE}"
  build_and_install
  exit 0
fi

verify_archive "${BATCH_ARCHIVE}" "${BATCH_SHA256}"
verify_archive "${WARMUP_ARCHIVE}" "${WARMUP_SHA256}"
verify_archive "${PROBE_ARCHIVE}" "${PROBE_SHA256}"

timestamp="$(date +%Y%m%d-%H%M%S)"
backup="${BACKUP_DIR}/optimized-before-rollback-${timestamp}.tar.gz"
tar -czf "${backup}" -C "${ROOT_DIR}" "${TRACKED_PATHS[@]}"
printf '%s\n' "${backup}" > "${LATEST_FILE}"

tar -xzf "${BATCH_ARCHIVE}" -C "${WORKSPACE_ROOT}"
tar -xzf "${WARMUP_ARCHIVE}" -C "${WORKSPACE_ROOT}"
tar -xzf "${PROBE_ARCHIVE}" -C "${ROOT_DIR}"

echo "Restored pre-optimization terminal renderer checkpoints"
echo "Optimized-source undo backup: ${backup}"
build_and_install
