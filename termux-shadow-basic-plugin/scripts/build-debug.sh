#!/data/data/com.termux/files/usr/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$ROOT_DIR/scripts/lib/common.sh"

publish=1
wait_for_registration=1
version_code=
version_name=

usage() {
    cat <<'EOF'
Usage: build-debug.sh [options]

Options:
  --validate-only       Build and validate without publishing.
  --version-code N      Override defaultVersionCode for this build.
  --version-name NAME   Override defaultVersionName for this build.
  --no-wait             Publish without waiting for host registration.
  -h, --help            Show this help.
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --validate-only)
            publish=0
            ;;
        --version-code)
            [ "$#" -ge 2 ] || { printf 'Missing --version-code value\n' >&2; exit 2; }
            version_code=$2
            shift
            ;;
        --version-name)
            [ "$#" -ge 2 ] || { printf 'Missing --version-name value\n' >&2; exit 2; }
            version_name=$2
            shift
            ;;
        --no-wait)
            wait_for_registration=0
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            printf 'Unknown build option: %s\n' "$1" >&2
            exit 2
            ;;
    esac
    shift
done

if [ "$publish" -eq 1 ]; then
    printf 'Publishing is owned by the native shadow-plugin CLI; use shadow-plugin publish or deploy.\n' >&2
    exit 69
fi

shadow_setup_build_env
aapt2=$(shadow_find_aapt2) || {
    printf 'aapt2 not found under %s/build-tools\n' "$ANDROID_HOME" >&2
    exit 1
}

tooling_fingerprint=$(
    for tooling_file in \
        "$ROOT_DIR/shadow-plugin.properties" \
        "$ROOT_DIR/build.gradle" \
        "$ROOT_DIR/settings.gradle" \
        "$ROOT_DIR/plugin-app/build.gradle" \
        "$ROOT_DIR/plugin-app/src/main/AndroidManifest.xml"; do
        sha256sum "$tooling_file"
    done | sha256sum | awk '{print $1}'
)
fingerprint_file=$ROOT_DIR/.gradle/shadow-tooling.fingerprint
previous_fingerprint=
[ ! -f "$fingerprint_file" ] || previous_fingerprint=$(sed -n '1p' "$fingerprint_file")
if [ "$previous_fingerprint" != "$tooling_fingerprint" ]; then
    printf 'Shadow tooling/config changed; invalidating stale build outputs.\n'
    rm -rf "$ROOT_DIR/build" "$ROOT_DIR/plugin-app/build"
fi

sh "$ROOT_DIR/scripts/doctor.sh"
task=copyShadowPluginDebugToDist

printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$ROOT_DIR/local.properties"

set -- --offline --no-daemon "-Pandroid.aapt2FromMavenOverride=$aapt2"
[ -z "$version_code" ] || set -- "$@" "-PshadowPluginVersionCode=$version_code"
[ -z "$version_name" ] || set -- "$@" "-PshadowPluginVersionName=$version_name"
set -- "$@" "$task"

cd "$ROOT_DIR"
./gradlew "$@"

mkdir -p "$ROOT_DIR/.gradle"
fingerprint_temp=$fingerprint_file.tmp.$$
printf '%s\n' "$tooling_fingerprint" > "$fingerprint_temp"
mv "$fingerprint_temp" "$fingerprint_file"

printf '\nValidated artifacts:\n'
find "$ROOT_DIR/dist" -maxdepth 1 -type f -name '*.shadowpkg' \
    ! -name 'active.shadowpkg' -print
