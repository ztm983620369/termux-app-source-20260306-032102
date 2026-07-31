#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly CORE_ARCHIVE="/root/termux-render-checkpoints/termux-before-vulkan-renderer-20260727.tar.gz"
readonly CORE_SHA256="b7c61640d3af1249a07eafd792259c9bd0698a3968019382f6450edd7dd7fe98"
readonly PROBE_ARCHIVE="/root/termux-render-checkpoints/termux-before-vulkan-instrumentation-20260727.tar.gz"
readonly PROBE_SHA256="4bbeda04db0d8f14c877a45dca613d716da86f7146b3d8ee479c3a175b828c4e"
readonly BACKUP_DIR="/root/termux-render-checkpoints/vulkan-undo"
readonly LATEST_FILE="${BACKUP_DIR}/latest"
readonly APK_RELATIVE="app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk"

readonly -a TRACKED_PATHS=(
  "app/src/androidTest/java/com/termux/terminal/TerminalIndustrialInstrumentation.java"
  "terminal-view/build.gradle"
  "terminal-view/src/main/cpp"
  "terminal-view/src/main/java/com/termux/view/GhosttyRenderNodeRenderer.java"
  "terminal-view/src/main/java/com/termux/view/TerminalRenderer.java"
  "terminal-view/src/main/java/com/termux/view/TerminalView.java"
  "terminal-view/src/main/java/com/termux/view/TerminalGpuFrame.java"
  "terminal-view/src/main/java/com/termux/view/TerminalVulkanRenderer.java"
  "terminal-view/src/main/java/com/termux/view/TerminalVulkanView.java"
  "terminal-view/src/test/java/com/termux/view/TerminalGpuFrameTest.java"
  "terminal-session-surface/src/main/java/com/termux/terminalsessionsurface/TerminalSessionSurfaceView.java"
  "terminal-session-surface/src/main/res/layout/item_terminal_session_page.xml"
  "terminal-session-surface/src/main/res/layout-v33/item_terminal_session_page.xml"
)

readonly -a VULKAN_ONLY_PATHS=(
  "terminal-view/src/main/cpp"
  "terminal-view/src/main/java/com/termux/view/TerminalGpuFrame.java"
  "terminal-view/src/main/java/com/termux/view/TerminalVulkanRenderer.java"
  "terminal-view/src/main/java/com/termux/view/TerminalVulkanView.java"
  "terminal-view/src/test/java/com/termux/view/TerminalGpuFrameTest.java"
  "terminal-session-surface/src/main/res/layout-v33/item_terminal_session_page.xml"
)

usage() {
  cat <<'EOF'
Usage:
  ./rollback-terminal-render-vulkan.sh [--source-only]
  ./rollback-terminal-render-vulkan.sh --undo [backup.tar.gz] [--source-only]

Restore the exact renderer sources from immediately before the independent Vulkan path.
The current optimized sources are archived first. --undo restores that archived version.
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

mkdir -p "${BACKUP_DIR}"
if [[ "${MODE}" == "undo" ]]; then
  if [[ -z "${UNDO_ARCHIVE}" ]]; then
    [[ -s "${LATEST_FILE}" ]] || { echo "No optimized-source backup recorded" >&2; exit 1; }
    UNDO_ARCHIVE="$(<"${LATEST_FILE}")"
  fi
  [[ -f "${UNDO_ARCHIVE}" ]] || { echo "Undo archive missing: ${UNDO_ARCHIVE}" >&2; exit 1; }
  tar -xzf "${UNDO_ARCHIVE}" -C "${ROOT_DIR}"
  echo "Restored Vulkan renderer sources from ${UNDO_ARCHIVE}"
  build_and_install
  exit 0
fi

verify_archive "${CORE_ARCHIVE}" "${CORE_SHA256}"
verify_archive "${PROBE_ARCHIVE}" "${PROBE_SHA256}"

timestamp="$(date +%Y%m%d-%H%M%S)"
backup="${BACKUP_DIR}/optimized-before-rollback-${timestamp}.tar.gz"
tar -czf "${backup}" -C "${ROOT_DIR}" "${TRACKED_PATHS[@]}"
printf '%s\n' "${backup}" > "${LATEST_FILE}"

for path in "${VULKAN_ONLY_PATHS[@]}"; do
  rm -rf "${ROOT_DIR}/${path}"
done
tar -xzf "${CORE_ARCHIVE}" -C "${ROOT_DIR}"
tar -xzf "${PROBE_ARCHIVE}" -C "${ROOT_DIR}"

echo "Restored the pre-Vulkan terminal renderer"
echo "Vulkan-source undo backup: ${backup}"
build_and_install
