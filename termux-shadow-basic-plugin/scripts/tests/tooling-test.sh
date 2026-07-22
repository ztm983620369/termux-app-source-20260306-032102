#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
. "$ROOT_DIR/scripts/lib/common.sh"

TMP_ROOT=$(mktemp -d)
cleanup() {
    rm -rf "$TMP_ROOT"
}
trap cleanup EXIT HUP INT TERM

fail() {
    printf 'tooling-test: %s\n' "$*" >&2
    exit 1
}

TERMUX_HOME=$TMP_ROOT/home
export TERMUX_HOME
mkdir -p "$TERMUX_HOME"

sh "$ROOT_DIR/scripts/doctor.sh" --project-only >/dev/null
sh "$ROOT_DIR/shadow-plugin" help >/dev/null

NOTES_PROJECT=$TERMUX_HOME/termux-shadow-notes
sh "$ROOT_DIR/scripts/new-plugin.sh" notes "Notes App" \
    --target "$NOTES_PROJECT" --resource-id 0x6A >/dev/null

[ "$(shadow_property "$NOTES_PROJECT/shadow-plugin.properties" pluginId)" \
    = com.termux.shadow.notes ] || fail "generated pluginId mismatch"
[ "$(shadow_property "$NOTES_PROJECT/shadow-plugin.properties" resourcePackageId)" \
    = 0x6A ] || fail "generated resource ID mismatch"
[ "$(shadow_property "$NOTES_PROJECT/shadow-plugin.properties" displayName)" \
    = "Notes App" ] || fail "display name with spaces was not preserved"
[ -f "$NOTES_PROJECT/plugin-app/src/main/java/com/termux/shadow/notes/NotesActivity.java" ] \
    || fail "generated Activity source is missing"
[ ! -e "$NOTES_PROJECT/build" ] || fail "build output was copied into generated project"
[ ! -e "$NOTES_PROJECT/dist" ] || fail "dist output was copied into generated project"
sh "$NOTES_PROJECT/scripts/doctor.sh" --project-only >/dev/null

if sh "$ROOT_DIR/scripts/new-plugin.sh" tasks "Tasks" \
        --target "$TERMUX_HOME/termux-shadow-tasks" --resource-id 0x6A >/dev/null 2>&1; then
    fail "duplicate resource ID was accepted"
fi

SHADOW_HOME=$TMP_ROOT/shadow-home
mkdir -p "$SHADOW_HOME/reports"
cat > "$SHADOW_HOME/reports/health.json" <<'EOF'
{
  "status": "READY",
  "registryRevision": 7,
  "ingressMode": "SHADOWPKG_INBOX_ONLY"
}
EOF
cat > "$SHADOW_HOME/reports/registry.json" <<'EOF'
{
  "revision": 7,
  "plugins": [
    {
      "pluginId": "com.termux.shadow.notes",
      "enabled": true,
      "activeGeneration": "1-0123456789abcdef",
      "previousGeneration": null,
      "candidateGeneration": null,
      "versions": [
        {
          "generation": "1-0123456789abcdef",
          "bundleSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        }
      ]
    }
  ]
}
EOF

status_output=$(sh "$NOTES_PROJECT/scripts/status.sh" --shadow-home "$SHADOW_HOME")
printf '%s\n' "$status_output" | grep -Fq 'com.termux.shadow.notes' \
    || fail "status did not list generated plugin"
printf '%s\n' "$status_output" | grep -Fq 'active: 1-0123456789abcdef' \
    || fail "status did not parse active generation"

mkdir -p "$NOTES_PROJECT/dist"
cat > "$NOTES_PROJECT/dist/last-published.json" <<'EOF'
{
  "pluginId": "com.termux.shadow.notes",
  "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "fileName": "notes-0123456789abcdef.shadowpkg"
}
EOF
sh "$NOTES_PROJECT/scripts/status.sh" --shadow-home "$SHADOW_HOME" \
    --wait --timeout 1 >/dev/null

cat > "$NOTES_PROJECT/dist/last-published.json" <<'EOF'
{
  "pluginId": "com.termux.shadow.notes",
  "sha256": "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
  "fileName": "notes-rejected.shadowpkg"
}
EOF
mkdir -p "$SHADOW_HOME/quarantine"
cat > "$SHADOW_HOME/quarantine/rejected.json" <<'EOF'
{
  "sourcePath": "/inbox/notes-rejected.shadowpkg",
  "error": "test rejection"
}
EOF
if sh "$NOTES_PROJECT/scripts/status.sh" --shadow-home "$SHADOW_HOME" \
        --wait --timeout 1 >/dev/null 2>&1; then
    fail "status accepted a quarantined publish"
fi

printf 'tooling-test: PASS\n'
