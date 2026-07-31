#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly CHECKPOINT="/root/termux-render-checkpoints/termux-before-scroll-hotpath-v2-20260727.tar.gz"
readonly CHECKPOINT_SHA256="047b36770d30011eb07c3c2c55e6ddc493a133eb99dfc68c64f1d7057ad71228"
readonly BACKUP_DIR="/root/termux-render-checkpoints/scroll-hotpath-v2-undo"
readonly LATEST_FILE="${BACKUP_DIR}/latest"
readonly APK_RELATIVE="app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk"

readonly -a PATHS=(
  "terminal-view/src/main/java/com/termux/view/TerminalRenderer.java"
  "terminal-view/src/main/java/com/termux/view/GhosttyRenderNodeRenderer.java"
  "terminal-view/src/main/java/com/termux/view/TerminalVulkanRenderer.java"
  "terminal-view/src/main/cpp/terminal_vulkan_renderer.c"
  "terminal-view/src/test/java/com/termux/view/TerminalVulkanRendererTest.java"
)

source_only=false
mode=rollback
undo_archive=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-only)
      source_only=true
      shift
      ;;
    --undo)
      mode=undo
      shift
      if [[ $# -gt 0 && "$1" != --* ]]; then
        undo_archive="$1"
        shift
      fi
      ;;
    *)
      echo "Usage: $0 [--source-only] [--undo [archive.tar.gz]]" >&2
      exit 2
      ;;
  esac
done

build_and_install() {
  [[ "${source_only}" == "true" ]] && return
  "${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" --offline --no-daemon :app:assembleDebug
  local apk="${ROOT_DIR}/${APK_RELATIVE}"
  [[ -f "${apk}" ]] || { echo "Rollback APK missing: ${apk}" >&2; exit 1; }
  command -v adb >/dev/null 2>&1 || { echo "Rollback built; adb unavailable: ${apk}"; return; }
  mapfile -t devices < <(adb devices | awk 'NR > 1 && $2 == "device" {print $1}')
  if [[ "${#devices[@]}" -eq 1 ]]; then
    adb -s "${devices[0]}" install --no-streaming -r -t "${apk}"
  else
    echo "Rollback built; expected one adb device, found ${#devices[@]}: ${apk}"
  fi
}

mkdir -p "${BACKUP_DIR}"
if [[ "${mode}" == "undo" ]]; then
  if [[ -z "${undo_archive}" ]]; then
    [[ -s "${LATEST_FILE}" ]] || { echo "No optimized-source backup recorded" >&2; exit 1; }
    undo_archive="$(<"${LATEST_FILE}")"
  fi
  [[ -f "${undo_archive}" ]] || { echo "Undo archive missing: ${undo_archive}" >&2; exit 1; }
  tar -xzf "${undo_archive}" -C "${ROOT_DIR}"
  echo "Restored optimized scroll hot path from ${undo_archive}"
  build_and_install
  exit 0
fi

actual_sha256="$(sha256sum "${CHECKPOINT}" | awk '{print $1}')"
[[ "${actual_sha256}" == "${CHECKPOINT_SHA256}" ]] || {
  echo "Checkpoint hash mismatch: ${actual_sha256}" >&2
  exit 1
}

backup="${BACKUP_DIR}/optimized-before-rollback-$(date +%Y%m%d-%H%M%S).tar.gz"
tar -czf "${backup}" -C "${ROOT_DIR}" "${PATHS[@]}"
printf '%s\n' "${backup}" > "${LATEST_FILE}"
tar -xzf "${CHECKPOINT}" -C "${ROOT_DIR}"
echo "Restored the pre-optimization terminal scroll hot path"
echo "Undo archive: ${backup}"
build_and_install
