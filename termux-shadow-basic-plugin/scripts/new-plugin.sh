#!/data/data/com.termux/files/usr/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$ROOT_DIR/scripts/lib/common.sh"

slug=
display_name=
description=
target=
plugin_id=
part_key=
namespace=
activity_simple=
resource_id=auto
publish=0
dry_run=0
allow_existing=0

usage() {
    cat <<'EOF'
Usage: new-plugin.sh <slug> [display-name] [options]

Options:
  --target PATH          Destination (default: ~/termux-shadow-<slug>).
  --plugin-id ID         Logical plugin ID (auto-derived by default).
  --part-key KEY         Shadow part key (auto-derived by default).
  --namespace NAME       Java namespace (auto-derived by default).
  --activity NAME        Simple Activity class name (auto-derived by default).
  --resource-id ID       Resource package ID or 'auto' (default).
  --description TEXT     Plugin metadata description.
  --publish              Doctor, build, publish, and wait for registration.
  --allow-existing       Allow a pluginId already present in the live registry.
  --dry-run              Print derived identity without creating files.
  -h, --help             Show this help.

Example:
  ./shadow-plugin new notes "Notes" --publish
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --target|--plugin-id|--part-key|--namespace|--activity|--resource-id|--description)
            [ "$#" -ge 2 ] || { printf 'Missing value for %s\n' "$1" >&2; exit 2; }
            option=$1
            value=$2
            shift
            case "$option" in
                --target) target=$value ;;
                --plugin-id) plugin_id=$value ;;
                --part-key) part_key=$value ;;
                --namespace) namespace=$value ;;
                --activity) activity_simple=$value ;;
                --resource-id) resource_id=$value ;;
                --description) description=$value ;;
            esac
            ;;
        --publish) publish=1 ;;
        --dry-run) dry_run=1 ;;
        --allow-existing) allow_existing=1 ;;
        -h|--help) usage; exit 0 ;;
        --*) printf 'Unknown new-plugin option: %s\n' "$1" >&2; exit 2 ;;
        *)
            if [ -z "$slug" ]; then
                slug=$1
            elif [ -z "$display_name" ]; then
                display_name=$1
            else
                printf 'Unexpected argument: %s\n' "$1" >&2
                exit 2
            fi
            ;;
    esac
    shift
done

[ -n "$slug" ] || { usage >&2; exit 2; }
printf '%s\n' "$slug" | grep -Eq '^[a-z][a-z0-9-]*$' \
    || { printf 'Invalid slug: %s\n' "$slug" >&2; exit 2; }

for text_value in "$display_name" "$description"; do
    case "$text_value" in
        *\\*) printf 'Display name/description cannot contain backslashes\n' >&2; exit 2 ;;
    esac
    line_count=$(printf '%s\n' "$text_value" | wc -l | tr -d ' ')
    [ "$line_count" -eq 1 ] \
        || { printf 'Display name/description must be one line\n' >&2; exit 2; }
done

slug_dots=$(printf '%s' "$slug" | tr - .)
pascal=$(printf '%s\n' "$slug" | awk -F- '{
    for (i = 1; i <= NF; i++) {
        printf "%s%s", toupper(substr($i, 1, 1)), substr($i, 2)
    }
}')
[ -n "$display_name" ] || display_name=$pascal
[ -n "$description" ] || description="Termux Shadow plugin: $display_name"
[ -n "$plugin_id" ] || plugin_id="com.termux.shadow.$slug_dots"
[ -n "$part_key" ] || part_key="termux-$slug-plugin"
[ -n "$namespace" ] || namespace="com.termux.shadow.$slug_dots"
[ -n "$activity_simple" ] || activity_simple="${pascal}Activity"
activity_class="$namespace.$activity_simple"
project_name="TermuxShadow${pascal}Plugin"
plugin_apk="termux-shadow-$slug-plugin-debug.apk"
bundle_base="termux-shadow-$slug"

printf '%s\n' "$plugin_id" | grep -Eq '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$' \
    || { printf 'Invalid plugin ID: %s\n' "$plugin_id" >&2; exit 2; }
printf '%s\n' "$part_key" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._-]*$' \
    || { printf 'Invalid part key: %s\n' "$part_key" >&2; exit 2; }
printf '%s\n' "$namespace" | grep -Eq '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$' \
    || { printf 'Invalid namespace: %s\n' "$namespace" >&2; exit 2; }
printf '%s\n' "$activity_simple" | grep -Eq '^[A-Za-z_][A-Za-z0-9_]*$' \
    || { printf 'Invalid Activity class: %s\n' "$activity_simple" >&2; exit 2; }

TERMUX_HOME=${TERMUX_HOME:-${HOME:-/data/data/com.termux/files/home}}
[ -n "$target" ] || target=$TERMUX_HOME/termux-shadow-$slug
target_parent=$(dirname -- "$target")
target_name=$(basename -- "$target")
mkdir -p "$target_parent"
target_parent=$(CDPATH= cd -- "$target_parent" && pwd -P)
target=$target_parent/$target_name

case "$target/" in
    "$ROOT_DIR"/*) printf 'Target cannot be inside the template project\n' >&2; exit 2 ;;
esac
[ ! -e "$target" ] || { printf 'Target already exists: %s\n' "$target" >&2; exit 1; }

registry=$TERMUX_HOME/.termux-shadow/reports/registry.json
if shadow_registry_has_plugin "$registry" "$plugin_id" && [ "$allow_existing" -ne 1 ]; then
    printf 'pluginId is already registered: %s (use --allow-existing to recover its source)\n' \
        "$plugin_id" >&2
    exit 1
fi

for sibling_config in "$TERMUX_HOME"/termux-shadow-*/shadow-plugin.properties; do
    [ -f "$sibling_config" ] || continue
    sibling_id=$(shadow_property "$sibling_config" pluginId)
    if [ "$sibling_id" = "$plugin_id" ]; then
        printf 'pluginId already belongs to project: %s\n' "$(dirname -- "$sibling_config")" >&2
        exit 1
    fi
done

resource_is_used() {
    candidate=$1
    owner=$(shadow_registered_resource_owner "$registry" "$candidate" || true)
    [ -z "$owner" ] || return 0
    for sibling_config in "$TERMUX_HOME"/termux-shadow-*/shadow-plugin.properties; do
        [ -f "$sibling_config" ] || continue
        sibling_resource=$(shadow_property "$sibling_config" resourcePackageId)
        if [ "$(printf '%s' "$sibling_resource" | tr 'a-fx' 'A-FX')" \
                = "$(printf '%s' "$candidate" | tr 'a-fx' 'A-FX')" ]; then
            return 0
        fi
    done
    return 1
}

if [ "$resource_id" = auto ]; then
    resource_id=
    candidate_decimal=123
    while [ "$candidate_decimal" -ge 2 ]; do
        candidate=$(printf '0x%02X' "$candidate_decimal")
        if ! resource_is_used "$candidate"; then
            resource_id=$candidate
            break
        fi
        candidate_decimal=$((candidate_decimal - 1))
    done
    if [ -z "$resource_id" ]; then
        for candidate in 0x7D 0x7E; do
            if ! resource_is_used "$candidate"; then
                resource_id=$candidate
                break
            fi
        done
    fi
    [ -n "$resource_id" ] || { printf 'No free Shadow resource package ID\n' >&2; exit 1; }
else
    case "$resource_id" in
        0[xX][0-9A-Fa-f][0-9A-Fa-f]) ;;
        *) printf 'Invalid resource package ID: %s\n' "$resource_id" >&2; exit 2 ;;
    esac
    resource_decimal=$(printf '%d' "$resource_id" 2>/dev/null || printf '0')
    if [ "$resource_decimal" -lt 2 ] || [ "$resource_decimal" -gt 126 ]; then
        printf 'Resource package ID must be in 0x02..0x7E\n' >&2
        exit 2
    fi
    resource_id=$(printf '0x%02X' "$resource_decimal")
    if resource_is_used "$resource_id"; then
        printf 'Resource package ID is already in use: %s\n' "$resource_id" >&2
        exit 1
    fi
fi

printf 'New Shadow plugin\n'
printf '  target: %s\n' "$target"
printf '  pluginId: %s\n' "$plugin_id"
printf '  partKey: %s\n' "$part_key"
printf '  namespace: %s\n' "$namespace"
printf '  activity: %s\n' "$activity_class"
printf '  resource ID: %s\n' "$resource_id"
printf '  publish: %s\n' "$publish"

[ "$dry_run" -eq 0 ] || exit 0

source_config=$ROOT_DIR/shadow-plugin.properties
old_namespace=$(shadow_property "$source_config" namespace)
old_activity=$(shadow_property "$source_config" activityClassName)
old_activity_simple=${old_activity##*.}
old_plugin_id=$(shadow_property "$source_config" pluginId)
old_part_key=$(shadow_property "$source_config" partKey)
min_host=$(shadow_property "$source_config" minHostVersionCode)
max_host=$(shadow_property "$source_config" maxHostVersionCode)

umask 077
temporary=$target_parent/.${target_name}.new.$$
created=0
cleanup() {
    if [ "$created" -eq 0 ] && [ -d "$temporary" ]; then
        rm -rf "$temporary"
    fi
}
trap cleanup EXIT HUP INT TERM
mkdir "$temporary"

(cd "$ROOT_DIR" && tar \
    --exclude='./.gradle' \
    --exclude='./build' \
    --exclude='./dist' \
    --exclude='./local.properties' \
    --exclude='./plugin-app/build' \
    -cf - .) | (cd "$temporary" && tar -xf -)

cat > "$temporary/shadow-plugin.properties" <<EOF
# UTF-8 Shadow plugin identity. This is the only editable identity/config source.
schemaVersion=1
pluginSlug=$slug
projectName=$project_name
pluginId=$plugin_id
partKey=$part_key
namespace=$namespace
activityClassName=$activity_class
resourcePackageId=$resource_id
pluginApkName=$plugin_apk
bundleBaseName=$bundle_base
displayName=$display_name
description=$description
defaultVersionCode=1
defaultVersionName=1.0.0
minHostVersionCode=$min_host
maxHostVersionCode=$max_host
EOF

find "$temporary/plugin-app/src" -type f \
    \( -name '*.java' -o -name '*.kt' -o -name '*.xml' \) -print \
    | while IFS= read -r source_file; do
        sed -i \
            -e "s/$old_namespace/$namespace/g" \
            -e "s/$old_activity_simple/$activity_simple/g" \
            -e "s/$old_plugin_id/$plugin_id/g" \
            -e "s/$old_part_key/$part_key/g" \
            "$source_file"
    done

old_namespace_path=$(printf '%s' "$old_namespace" | tr . /)
new_namespace_path=$(printf '%s' "$namespace" | tr . /)
for source_kind in java kotlin; do
    old_source_dir=$temporary/plugin-app/src/main/$source_kind/$old_namespace_path
    new_source_dir=$temporary/plugin-app/src/main/$source_kind/$new_namespace_path
    if [ "$old_source_dir" != "$new_source_dir" ] && [ -d "$old_source_dir" ]; then
        mkdir -p "$(dirname -- "$new_source_dir")"
        mv "$old_source_dir" "$new_source_dir"
    fi
    for extension in java kt; do
        if [ -f "$new_source_dir/$old_activity_simple.$extension" ] \
                && [ "$old_activity_simple" != "$activity_simple" ]; then
            mv "$new_source_dir/$old_activity_simple.$extension" \
                "$new_source_dir/$activity_simple.$extension"
        fi
    done
done

sh "$temporary/scripts/doctor.sh" --project-only
mv "$temporary" "$target"
created=1
trap - EXIT HUP INT TERM

printf '\nCreated: %s\n' "$target"
if [ "$publish" -eq 1 ]; then
    printf '\nBuilding, publishing, and verifying registration...\n'
    sh "$target/scripts/build-debug.sh"
else
    printf 'Next: cd %s && ./shadow-plugin publish\n' "$target"
fi
