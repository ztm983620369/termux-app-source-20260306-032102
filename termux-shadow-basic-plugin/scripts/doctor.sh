#!/data/data/com.termux/files/usr/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$ROOT_DIR/scripts/lib/common.sh"

project_only=0
publish_mode=0
full=0

usage() {
    cat <<'EOF'
Usage: doctor.sh [--project-only] [--publish] [--full]

  --project-only  Check config and source without requiring Android/Termux tools.
  --publish       Require the real com.termux home and publishing environment.
  --full          Run the Gradle package validator after fast checks.
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --project-only) project_only=1 ;;
        --publish) publish_mode=1 ;;
        --full) full=1 ;;
        -h|--help) usage; exit 0 ;;
        *) printf 'Unknown doctor option: %s\n' "$1" >&2; exit 2 ;;
    esac
    shift
done

failures=0
warnings=0

ok() { printf '[OK]   %s\n' "$*"; }
warn() { warnings=$((warnings + 1)); printf '[WARN] %s\n' "$*"; }
fail() { failures=$((failures + 1)); printf '[FAIL] %s\n' "$*"; }

CONFIG=$ROOT_DIR/shadow-plugin.properties
printf 'Shadow plugin doctor\n  project: %s\n\n' "$ROOT_DIR"

if [ ! -f "$CONFIG" ]; then
    fail "missing shadow-plugin.properties"
    printf '\nDoctor failed: %s error(s), %s warning(s)\n' "$failures" "$warnings"
    exit 1
fi

duplicates=$(awk -F= '
    /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
    {
        key = $1
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
        count[key]++
    }
    END {
        for (key in count) if (count[key] > 1) print key
    }
' "$CONFIG" | sort)
if [ -n "$duplicates" ]; then
    fail "duplicate properties: $(printf '%s' "$duplicates" | tr '\n' ' ')"
else
    ok "property keys are unique"
fi

required_keys='schemaVersion pluginSlug projectName pluginId partKey namespace activityClassName resourcePackageId pluginApkName bundleBaseName displayName description defaultVersionCode defaultVersionName minHostVersionCode maxHostVersionCode'
for key in $required_keys; do
    value=$(shadow_property "$CONFIG" "$key")
    if [ -z "$value" ]; then
        fail "missing property: $key"
    fi
done

schema=$(shadow_property "$CONFIG" schemaVersion)
slug=$(shadow_property "$CONFIG" pluginSlug)
project_name=$(shadow_property "$CONFIG" projectName)
plugin_id=$(shadow_property "$CONFIG" pluginId)
part_key=$(shadow_property "$CONFIG" partKey)
namespace=$(shadow_property "$CONFIG" namespace)
activity=$(shadow_property "$CONFIG" activityClassName)
resource_id=$(shadow_property "$CONFIG" resourcePackageId)
plugin_apk=$(shadow_property "$CONFIG" pluginApkName)
bundle_base=$(shadow_property "$CONFIG" bundleBaseName)
version_code=$(shadow_property "$CONFIG" defaultVersionCode)
version_name=$(shadow_property "$CONFIG" defaultVersionName)
min_host=$(shadow_property "$CONFIG" minHostVersionCode)
max_host=$(shadow_property "$CONFIG" maxHostVersionCode)

printf '  pluginId: %s\n  partKey: %s\n  resource: %s\n\n' \
    "$plugin_id" "$part_key" "$resource_id"

[ "$schema" = 1 ] && ok "config schema is 1" || fail "schemaVersion must be 1"
printf '%s\n' "$slug" | grep -Eq '^[a-z][a-z0-9-]*$' \
    && ok "pluginSlug is valid" || fail "invalid pluginSlug: $slug"
printf '%s\n' "$project_name" | grep -Eq '^[A-Za-z][A-Za-z0-9_-]*$' \
    && ok "projectName is valid" || fail "invalid projectName: $project_name"
printf '%s\n' "$plugin_id" | grep -Eq '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$' \
    && ok "pluginId is valid" || fail "invalid pluginId: $plugin_id"
printf '%s\n' "$part_key" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._-]*$' \
    && ok "partKey is valid" || fail "invalid partKey: $part_key"
printf '%s\n' "$namespace" | grep -Eq '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$' \
    && ok "namespace is valid" || fail "invalid namespace: $namespace"
case "$activity" in
    "$namespace".*) ok "activity belongs to namespace" ;;
    *) fail "activityClassName must be inside namespace" ;;
esac

case "$resource_id" in
    0[xX][0-9A-Fa-f][0-9A-Fa-f])
        resource_decimal=$(printf '%d' "$resource_id" 2>/dev/null || printf '0')
        if [ "$resource_decimal" -ge 2 ] && [ "$resource_decimal" -le 126 ]; then
            ok "resourcePackageId is in 0x02..0x7E"
        else
            fail "resourcePackageId is outside 0x02..0x7E: $resource_id"
        fi
        ;;
    *) fail "invalid resourcePackageId: $resource_id" ;;
esac

printf '%s\n' "$plugin_apk" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._-]*\.apk$' \
    && ok "pluginApkName is valid" || fail "invalid pluginApkName: $plugin_apk"
printf '%s\n' "$bundle_base" | grep -Eq '^[a-z0-9][a-z0-9._-]*$' \
    && ok "bundleBaseName is valid" || fail "invalid bundleBaseName: $bundle_base"
printf '%s\n' "$version_code" | grep -Eq '^[1-9][0-9]*$' \
    && ok "defaultVersionCode is positive" || fail "invalid defaultVersionCode: $version_code"
printf '%s\n' "$version_name" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._+-]*$' \
    && ok "defaultVersionName is valid" || fail "invalid defaultVersionName: $version_name"

if printf '%s\n%s\n' "$min_host" "$max_host" | grep -Eqv '^[1-9][0-9]*$'; then
    fail "host version bounds must be positive integers"
elif [ "$min_host" -le "$max_host" ]; then
    ok "host compatibility range is ordered"
else
    fail "minHostVersionCode exceeds maxHostVersionCode"
fi

activity_path=$(printf '%s' "$activity" | tr . /)
if [ -f "$ROOT_DIR/plugin-app/src/main/java/$activity_path.java" ] \
        || [ -f "$ROOT_DIR/plugin-app/src/main/kotlin/$activity_path.kt" ]; then
    ok "activity source exists"
else
    fail "activity source not found: $activity"
fi

if grep -Fq '${shadowActivityClassName}' "$ROOT_DIR/plugin-app/src/main/AndroidManifest.xml" \
        && grep -Fq '${shadowDisplayName}' "$ROOT_DIR/plugin-app/src/main/AndroidManifest.xml"; then
    ok "manifest identity is property-driven"
else
    fail "manifest is not using Shadow identity placeholders"
fi

[ -f "$ROOT_DIR/shadow/loader/sample-loader-debug.apk" ] \
    && ok "loader APK exists" || fail "missing loader APK"
[ -f "$ROOT_DIR/shadow/runtime/sample-runtime-debug.apk" ] \
    && ok "runtime APK exists" || fail "missing runtime APK"
[ -f "$ROOT_DIR/shadow/compile-only/shadow-runtime.jar" ] \
    && ok "compile-only Shadow runtime exists" || fail "missing shadow-runtime.jar"
[ -x "$ROOT_DIR/gradlew" ] && ok "Gradle wrapper is executable" || fail "gradlew is not executable"

if find "$ROOT_DIR" -path "$ROOT_DIR/.gradle" -prune -o -path "$ROOT_DIR/build" -prune \
        -o -path "$ROOT_DIR/plugin-app/build" -prune -o -type f -name 'plugin-debug.zip' -print \
        | grep -q .; then
    fail "legacy plugin-debug.zip exists in project"
else
    ok "no legacy plugin-debug.zip exists"
fi

if [ "$project_only" -eq 0 ]; then
    shadow_setup_build_env
    java_binary=$JAVA_HOME/bin/java
    [ -x "$java_binary" ] && ok "Java runtime: $java_binary" || fail "Java runtime not found: $java_binary"
    if aapt2=$(shadow_find_aapt2); then
        ok "aapt2: $aapt2"
    else
        fail "aapt2 not found under $ANDROID_HOME/build-tools"
        aapt2=
    fi
    [ -f "$ANDROID_HOME/platforms/android-35/android.jar" ] \
        && ok "Android 35 platform exists" || fail "Android 35 platform is missing"

    shadow_home=$TERMUX_HOME/.termux-shadow
    registry=$shadow_home/reports/registry.json
    owner=$(shadow_registered_resource_owner "$registry" "$resource_id" || true)
    if [ -n "$owner" ] && [ "$owner" != "$plugin_id" ]; then
        fail "resourcePackageId $resource_id is registered by $owner"
    elif [ -n "$owner" ]; then
        ok "registered resource ID belongs to this plugin"
    elif [ -f "$registry" ]; then
        ok "resource ID is free in live registry"
    else
        warn "live registry report is unavailable"
    fi

    current_root=$(CDPATH= cd -- "$ROOT_DIR" && pwd -P)
    for sibling_config in "$TERMUX_HOME"/termux-shadow-*/shadow-plugin.properties; do
        [ -f "$sibling_config" ] || continue
        sibling_root=$(CDPATH= cd -- "$(dirname -- "$sibling_config")" && pwd -P)
        [ "$sibling_root" = "$current_root" ] && continue
        sibling_id=$(shadow_property "$sibling_config" pluginId)
        sibling_resource=$(shadow_property "$sibling_config" resourcePackageId)
        if [ "$sibling_id" = "$plugin_id" ]; then
            fail "pluginId is duplicated by project: $sibling_root"
        fi
        if [ "$(printf '%s' "$sibling_resource" | tr 'a-fx' 'A-FX')" \
                = "$(printf '%s' "$resource_id" | tr 'a-fx' 'A-FX')" ]; then
            fail "resourcePackageId is duplicated by project: $sibling_root"
        fi
    done

    if [ "$publish_mode" -eq 1 ]; then
        if shadow_is_termux_home "$TERMUX_HOME"; then
            ok "publisher is inside the com.termux home"
        else
            fail "publishing is forbidden outside the com.termux home: $TERMUX_HOME"
        fi
        if [ -f "$shadow_home/plugin-debug.zip" ]; then
            fail "legacy fixed package exists: $shadow_home/plugin-debug.zip"
        else
            ok "managed home has no legacy fixed package"
        fi
    fi

    if [ -n "${TERMUX_SHADOW_SIGNING_KEY_PKCS8:-}" ] \
            || [ -n "${TERMUX_SHADOW_SIGNING_KEY_ID:-}" ]; then
        if [ -n "${TERMUX_SHADOW_SIGNING_KEY_PKCS8:-}" ] \
                && [ -n "${TERMUX_SHADOW_SIGNING_KEY_ID:-}" ] \
                && [ -f "$TERMUX_SHADOW_SIGNING_KEY_PKCS8" ]; then
            ok "release signing inputs are paired"
        else
            fail "release signing key path and key ID must be valid and set together"
        fi
    fi
fi

if [ "$failures" -eq 0 ] && [ "$full" -eq 1 ]; then
    if [ "$project_only" -eq 1 ]; then
        fail "--full cannot be combined with --project-only"
    else
        printf '\nRunning full Gradle package validation...\n'
        printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$ROOT_DIR/local.properties"
        if (cd "$ROOT_DIR" && ./gradlew --offline --no-daemon \
                "-Pandroid.aapt2FromMavenOverride=$aapt2" validateShadowPluginDebug); then
            ok "full package validation passed"
        else
            fail "full package validation failed"
        fi
    fi
fi

printf '\nDoctor summary: %s error(s), %s warning(s)\n' "$failures" "$warnings"
[ "$failures" -eq 0 ]
