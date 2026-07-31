#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly GITHUB_BASE="0b988510954e2ac5442409ad81d321c0f506f156"
readonly BACKUP_DIR="${XDG_STATE_HOME:-${HOME}/.local/state}/termux-render-v2-backups"
readonly LATEST_FILE="${BACKUP_DIR}/latest"
readonly APK_RELATIVE="app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk"

readonly -a TRACKED_FRONTEND_FILES=(
  "app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java"
  "app/src/androidTest/java/com/termux/shadow/ShadowProbeInstrumentation.java"
  "terminal-view/src/main/java/com/termux/view/GestureAndScaleRecognizer.java"
  "terminal-view/src/main/java/com/termux/view/TerminalRenderer.java"
  "terminal-view/src/main/java/com/termux/view/TerminalView.java"
  "terminal-view/src/main/java/com/termux/view/TerminalViewClient.java"
  "terminal-session-surface/src/main/java/com/termux/terminalsessionsurface/ProgrammaticViewPager.java"
  "terminal-session-surface/src/main/java/com/termux/terminalsessionsurface/TerminalSessionSurfaceRenderPolicy.java"
  "terminal-session-surface/src/main/java/com/termux/terminalsessionsurface/TerminalSessionSurfaceView.java"
  "terminal-session-surface/src/test/java/com/termux/terminalsessionsurface/TerminalSessionSurfaceRenderPolicyTest.java"
  "termux-shared/src/main/java/com/termux/shared/termux/terminal/TermuxTerminalViewClientBase.java"
)
readonly -a V2_ONLY_FILES=(
  "terminal-view/src/main/java/com/termux/view/GhosttyRenderNodeRenderer.java"
  "terminal-view/src/main/java/com/termux/view/TerminalFingerScrollTracker.java"
  "terminal-view/src/main/java/com/termux/view/TerminalViewportPosition.java"
  "terminal-view/src/debug/java/com/termux/view/GhosttyViewportRenderProbe.java"
  "terminal-view/src/test/java/com/termux/view/TerminalFingerScrollTrackerTest.java"
  "terminal-view/src/test/java/com/termux/view/TerminalViewportPositionTest.java"
  "terminal-session-surface/src/main/java/com/termux/terminalsessionsurface/TerminalSessionTransitionFrameState.java"
  "terminal-session-surface/src/test/java/com/termux/terminalsessionsurface/TerminalSessionTransitionFrameStateTest.java"
)

usage() {
  cat <<'EOF'
Usage:
  ./rollback-terminal-render-v2.sh [--source-only]
  ./rollback-terminal-render-v2.sh --undo [backup.tar.gz] [--source-only]

The default action restores only the V2 frontend/gesture chain to the pinned GitHub
baseline, builds the debug APK, and installs it when exactly one adb device is online.
The Ghostty parser/backend and unrelated worktree changes are left untouched.
EOF
}

build_and_install() {
  if [[ "${SOURCE_ONLY}" == "true" ]]; then
    return
  fi

  "${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" :app:assembleDebug
  local apk="${ROOT_DIR}/${APK_RELATIVE}"
  if [[ ! -f "${apk}" ]]; then
    echo "APK not found after build: ${apk}" >&2
    exit 1
  fi

  if ! command -v adb >/dev/null 2>&1; then
    echo "Rollback built. adb is unavailable; APK: ${apk}"
    return
  fi

  mapfile -t devices < <(adb devices | awk 'NR > 1 && $2 == "device" {print $1}')
  if [[ "${#devices[@]}" -eq 1 ]]; then
    adb -s "${devices[0]}" install -r -d "${apk}"
    echo "Rollback built and installed on ${devices[0]}"
  else
    echo "Rollback built. Expected one adb device, found ${#devices[@]}; APK: ${apk}"
  fi
}

SOURCE_ONLY=false
MODE=rollback
ARCHIVE=""
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
        ARCHIVE="$1"
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

cd "${ROOT_DIR}"
mkdir -p "${BACKUP_DIR}"

if [[ "${MODE}" == "undo" ]]; then
  if [[ -z "${ARCHIVE}" ]]; then
    if [[ ! -s "${LATEST_FILE}" ]]; then
      echo "No V2 rollback backup is recorded in ${LATEST_FILE}" >&2
      exit 1
    fi
    ARCHIVE="$(<"${LATEST_FILE}")"
  fi
  if [[ ! -f "${ARCHIVE}" ]]; then
    echo "Rollback backup not found: ${ARCHIVE}" >&2
    exit 1
  fi
  tar -xzf "${ARCHIVE}" -C "${ROOT_DIR}"
  echo "Restored V2 frontend sources from ${ARCHIVE}"
  build_and_install
  exit 0
fi

git cat-file -e "${GITHUB_BASE}^{commit}"
timestamp="$(date +%Y%m%d-%H%M%S)"
archive="${BACKUP_DIR}/v2-before-rollback-${timestamp}.tar.gz"
tar -czf "${archive}" "${TRACKED_FRONTEND_FILES[@]}" "${V2_ONLY_FILES[@]}"
printf '%s\n' "${archive}" > "${LATEST_FILE}"

git restore --source="${GITHUB_BASE}" -- "${TRACKED_FRONTEND_FILES[@]}"
rm -f "${V2_ONLY_FILES[@]}"

echo "V2 frontend rolled back to GitHub baseline ${GITHUB_BASE}"
echo "Pre-rollback sources saved at ${archive}"
build_and_install
