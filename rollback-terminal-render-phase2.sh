#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly DEFAULT_ARCHIVE="/root/.codex/checkpoints/termux-phase1-before-phase2-20260725.tar.gz"
readonly EXPECTED_SHA256="2c589987509fd464c82bc02d2eff81e541bac3e4407c90a434c558c8c4edc351"
readonly APK_RELATIVE="app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk"

usage() {
  cat <<'EOF'
Usage:
  ./rollback-terminal-render-phase2.sh [--source-only]
  ./rollback-terminal-render-phase2.sh --archive /path/to/checkpoint.tar.gz [--source-only]

Restores the phase-1 terminal source checkpoint. This action is intentionally
explicit and is never run as part of a normal build.
EOF
}

SOURCE_ONLY=false
ARCHIVE="${DEFAULT_ARCHIVE}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-only)
      SOURCE_ONLY=true
      shift
      ;;
    --archive)
      [[ $# -ge 2 ]] || { echo "--archive requires a path" >&2; exit 2; }
      ARCHIVE="$2"
      shift 2
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

cd "${ROOT_DIR}"
[[ -f "${ARCHIVE}" ]] || { echo "Checkpoint not found: ${ARCHIVE}" >&2; exit 1; }
actual_sha256="$(sha256sum "${ARCHIVE}" | awk '{print $1}')"
[[ "${actual_sha256}" == "${EXPECTED_SHA256}" ]] || {
  echo "Checkpoint hash mismatch: expected ${EXPECTED_SHA256}, got ${actual_sha256}" >&2
  exit 1
}

# These files were introduced after the checkpoint and tar extraction cannot delete them.
rm -f \
  terminal-session-surface/src/main/java/com/termux/terminalsessionsurface/TerminalSessionRenderWorkQueue.java \
  terminal-session-surface/src/test/java/com/termux/terminalsessionsurface/TerminalSessionRenderWorkQueueTest.java \
  docs/terminal-render-phase2-20260725.md

# Extract source/configuration paths only; stale phase-2 build intermediates are not restored.
tar -xzf "${ARCHIVE}" -C "${ROOT_DIR}" \
  app/src/androidTest/java/com/termux/terminal/TerminalIndustrialInstrumentation.java \
  terminal-emulator/src terminal-emulator/build.gradle terminal-emulator/proguard-rules.pro \
  terminal-view/src terminal-view/build.gradle terminal-view/proguard-rules.pro \
  terminal-session-surface/src terminal-session-surface/build.gradle \
  docs/terminal-core-ghostty-gpu-upgrade-20260724.md

echo "Restored phase-1 terminal source checkpoint ${EXPECTED_SHA256}"

if [[ "${SOURCE_ONLY}" == "true" ]]; then
  exit 0
fi

"${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" :app:assembleDebug
apk="${ROOT_DIR}/${APK_RELATIVE}"
if [[ ! -f "${apk}" ]]; then
  echo "APK not found after rollback build: ${apk}" >&2
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "Rollback built. adb is unavailable; APK: ${apk}"
  exit 0
fi

mapfile -t devices < <(adb devices | awk 'NR > 1 && $2 == "device" {print $1}')
if [[ "${#devices[@]}" -eq 1 ]]; then
  adb -s "${devices[0]}" install -r -d "${apk}"
  echo "Rollback built and installed on ${devices[0]}"
else
  echo "Rollback built. Expected one adb device, found ${#devices[@]}; APK: ${apk}"
fi
