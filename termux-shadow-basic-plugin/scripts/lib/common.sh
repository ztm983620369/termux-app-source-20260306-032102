shadow_property() {
    shadow_property_file=$1
    shadow_property_key=$2
    awk -v key="$shadow_property_key" '
        index($0, key "=") == 1 {
            print substr($0, length(key) + 2)
            exit
        }
    ' "$shadow_property_file"
}

shadow_json_value() {
    shadow_json_file=$1
    shadow_json_key=$2
    [ -f "$shadow_json_file" ] || return 1
    awk -v key="\"$shadow_json_key\"" '
        index($0, key) {
            line = $0
            sub(/^[^:]*:[[:space:]]*/, "", line)
            sub(/,[[:space:]]*$/, "", line)
            if (line ~ /^"/) {
                sub(/^"/, "", line)
                sub(/"$/, "", line)
            }
            print line
            exit
        }
    ' "$shadow_json_file"
}

shadow_setup_build_env() {
    TERMUX_HOME=${TERMUX_HOME:-${HOME:-/data/data/com.termux/files/home}}
    PREFIX=${PREFIX:-/data/data/com.termux/files/usr}
    portable_root=${TERMUX_SHADOW_ANDROID_TOOLCHAIN:-$TERMUX_HOME/android-minimal-basic-portable}
    if [ -x "$portable_root/toolchain/usr/lib/jvm/java-17-openjdk/bin/java" ] \
            && [ -x "$portable_root/project/android-sdk/build-tools/35.0.0/aapt2" ] \
            && [ -d "$portable_root/gradle-home" ]; then
        JAVA_HOME=$portable_root/toolchain/usr/lib/jvm/java-17-openjdk
        GRADLE_USER_HOME=$portable_root/gradle-home
        ANDROID_HOME=$portable_root/project/android-sdk
        TMPDIR=$portable_root/runtime/tmp
        PATH="$JAVA_HOME/bin:$ANDROID_HOME/build-tools/35.0.0:$portable_root/toolchain/usr/bin:/system/bin:/system/xbin"
        LD_LIBRARY_PATH="$portable_root/toolchain/usr/lib:$JAVA_HOME/lib:$JAVA_HOME/lib/server"
        mkdir -p "$TMPDIR"
        export GRADLE_USER_HOME TMPDIR LD_LIBRARY_PATH
    else
        ANDROID_HOME=${ANDROID_HOME:-$TERMUX_HOME/android-minimal-basic/android-sdk}
        JAVA_HOME=${JAVA_HOME:-$PREFIX/lib/jvm/java-17-openjdk}
        PATH="$PREFIX/bin:$PREFIX/bin/applets:$PATH"
    fi
    ANDROID_SDK_ROOT=$ANDROID_HOME
    export TERMUX_HOME PREFIX ANDROID_HOME ANDROID_SDK_ROOT JAVA_HOME PATH
}

shadow_find_aapt2() {
    shadow_preferred_aapt2=${ANDROID_HOME:-}/build-tools/35.0.0/aapt2
    if [ -f "$shadow_preferred_aapt2" ]; then
        printf '%s\n' "$shadow_preferred_aapt2"
        return 0
    fi
    shadow_aapt2=
    if [ -d "${ANDROID_HOME:-}/build-tools" ]; then
        shadow_aapt2=$(find "$ANDROID_HOME/build-tools" -mindepth 2 -maxdepth 2 \
            -type f -name aapt2 2>/dev/null | sort | tail -n 1)
    fi
    [ -n "$shadow_aapt2" ] || return 1
    printf '%s\n' "$shadow_aapt2"
}

shadow_is_termux_home() {
    shadow_home=$1
    case "$shadow_home" in
        /data/data/com.termux/files/home|/data/user/[0-9]*/com.termux/files/home)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

shadow_sha256() {
    sha256sum "$1" | awk '{print $1}'
}

shadow_registry_has_plugin() {
    shadow_registry=$1
    shadow_plugin_id=$2
    [ -f "$shadow_registry" ] \
        && grep -Fq "\"pluginId\": \"$shadow_plugin_id\"" "$shadow_registry"
}

shadow_registry_has_sha() {
    shadow_registry=$1
    shadow_sha=$2
    [ -f "$shadow_registry" ] \
        && grep -Fq "\"bundleSha256\": \"$shadow_sha\"" "$shadow_registry"
}

shadow_registered_resource_owner() {
    shadow_registry=$1
    shadow_resource_id=$2
    [ -f "$shadow_registry" ] || return 1
    awk -v wanted="$shadow_resource_id" '
        function value(line) {
            sub(/^[^:]*:[[:space:]]*"/, "", line)
            sub(/".*$/, "", line)
            return line
        }
        /"pluginId"[[:space:]]*:/ {
            plugin_id = value($0)
        }
        /"resourcePackageId"[[:space:]]*:/ {
            resource_id = value($0)
            if (toupper(resource_id) == toupper(wanted)) {
                print plugin_id
                exit
            }
        }
    ' "$shadow_registry"
}

shadow_config_file() {
    printf '%s/shadow-plugin.properties\n' "$1"
}
