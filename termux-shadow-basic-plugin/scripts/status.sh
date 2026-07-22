#!/data/data/com.termux/files/usr/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$ROOT_DIR/scripts/lib/common.sh"

show_all=0
wait_for_registration=0
timeout=45
shadow_home_override=
raw=0

usage() {
    cat <<'EOF'
Usage: status.sh [--all] [--wait] [--timeout SECONDS] [--raw]

  --all                 List every registered logical plugin.
  --wait                Wait for this project's latest receipt SHA to register.
  --timeout SECONDS     Registration wait timeout (default: 45).
  --shadow-home PATH    Read status from PATH (read-only/testing).
  --raw                 Print raw health and registry reports.
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --all) show_all=1 ;;
        --wait) wait_for_registration=1 ;;
        --timeout)
            [ "$#" -ge 2 ] || { printf 'Missing --timeout value\n' >&2; exit 2; }
            timeout=$2
            shift
            ;;
        --shadow-home)
            [ "$#" -ge 2 ] || { printf 'Missing --shadow-home value\n' >&2; exit 2; }
            shadow_home_override=$2
            shift
            ;;
        --raw) raw=1 ;;
        -h|--help) usage; exit 0 ;;
        *) printf 'Unknown status option: %s\n' "$1" >&2; exit 2 ;;
    esac
    shift
done

printf '%s\n' "$timeout" | grep -Eq '^[1-9][0-9]*$' \
    || { printf 'Timeout must be a positive integer\n' >&2; exit 2; }

CONFIG=$ROOT_DIR/shadow-plugin.properties
plugin_id=$(shadow_property "$CONFIG" pluginId)
TERMUX_HOME=${TERMUX_HOME:-${HOME:-/data/data/com.termux/files/home}}
if [ -n "$shadow_home_override" ]; then
    SHADOW_HOME=$shadow_home_override
else
    SHADOW_HOME=$TERMUX_HOME/.termux-shadow
fi
health=$SHADOW_HOME/reports/health.json
registry=$SHADOW_HOME/reports/registry.json
local_receipt=$ROOT_DIR/dist/last-published.json
global_receipt=$SHADOW_HOME/last-published.json
receipt=$local_receipt
platform_receipt=
if [ ! -f "$receipt" ]; then
    receipt=
    if [ -f "$global_receipt" ]; then
        global_plugin=$(shadow_json_value "$global_receipt" pluginId || true)
        if [ "$global_plugin" = "$plugin_id" ]; then
            receipt=$global_receipt
        else
            platform_receipt=$global_receipt
        fi
    fi
fi

if [ "$wait_for_registration" -eq 1 ]; then
    if [ ! -f "$receipt" ]; then
        printf 'No publish receipt found for %s\n' "$plugin_id" >&2
        exit 1
    fi
    receipt_plugin=$(shadow_json_value "$receipt" pluginId || true)
    receipt_sha=$(shadow_json_value "$receipt" sha256 || true)
    receipt_file=$(shadow_json_value "$receipt" fileName || true)
    if [ "$receipt_plugin" != "$plugin_id" ] || [ -z "$receipt_sha" ]; then
        printf 'Latest receipt does not belong to %s\n' "$plugin_id" >&2
        exit 1
    fi

    printf 'Waiting for host registration: %s\n' "$receipt_sha"
    started=$(date +%s)
    while :; do
        if shadow_registry_has_sha "$registry" "$receipt_sha"; then
            printf 'Registration confirmed: %s\n\n' "$receipt_sha"
            break
        fi
        for sidecar in "$SHADOW_HOME"/quarantine/*.json; do
            [ -f "$sidecar" ] || continue
            if [ -n "$receipt_file" ] && grep -Fq "$receipt_file" "$sidecar"; then
                error=$(shadow_json_value "$sidecar" error || true)
                printf 'Package was quarantined: %s\n' "${error:-unknown error}" >&2
                exit 1
            fi
        done
        now=$(date +%s)
        if [ $((now - started)) -ge "$timeout" ]; then
            printf 'Registration timed out after %ss. Open the Shadow host and refresh.\n' \
                "$timeout" >&2
            exit 1
        fi
        sleep 1
    done
fi

if [ "$raw" -eq 1 ]; then
    [ ! -f "$health" ] || cat "$health"
    [ ! -f "$registry" ] || cat "$registry"
    exit 0
fi

printf 'Shadow platform\n'
printf '  home: %s\n' "$SHADOW_HOME"
if [ -f "$health" ]; then
    printf '  status: %s\n' "$(shadow_json_value "$health" status || printf unknown)"
    printf '  ingress: %s\n' "$(shadow_json_value "$health" ingressMode || printf unknown)"
    printf '  registry revision: %s\n' \
        "$(shadow_json_value "$health" registryRevision || printf unknown)"
else
    printf '  status: unavailable (host report not found)\n'
fi

printf '\nCurrent project\n'
printf '  pluginId: %s\n' "$plugin_id"
printf '  config: %s\n' "$CONFIG"
if [ -n "$receipt" ] && [ -f "$receipt" ]; then
    printf '  last receipt: %s\n' "$receipt"
    printf '  published SHA: %s\n' "$(shadow_json_value "$receipt" sha256 || printf unknown)"
    printf '  published file: %s\n' "$(shadow_json_value "$receipt" fileName || printf unknown)"
else
    printf '  last receipt: none for current project\n'
fi

if [ -n "$platform_receipt" ]; then
    printf '\nPlatform latest publish\n'
    printf '  pluginId: %s\n' \
        "$(shadow_json_value "$platform_receipt" pluginId || printf unknown)"
    printf '  SHA: %s\n' "$(shadow_json_value "$platform_receipt" sha256 || printf unknown)"
    printf '  file: %s\n' "$(shadow_json_value "$platform_receipt" fileName || printf unknown)"
fi

printf '\nRegistered plugins\n'
if [ ! -f "$registry" ]; then
    printf '  registry report unavailable\n'
    exit 0
fi

awk -v wanted="$plugin_id" -v show_all="$show_all" '
    function value(line) {
        sub(/^[^:]*:[[:space:]]*/, "", line)
        sub(/,[[:space:]]*$/, "", line)
        sub(/^"/, "", line)
        sub(/"$/, "", line)
        return line
    }
    function emit() {
        if (plugin_id == "") return
        if (show_all == 1 || plugin_id == wanted) {
            print "  " plugin_id
            print "    enabled: " enabled
            print "    active: " active
            print "    candidate: " candidate
            print "    previous: " previous
            matched = 1
        }
    }
    {
        scan = $0
        opens = gsub(/\{/, "{", scan)
        closes = gsub(/\}/, "}", scan)
        if (depth == 2) {
            if ($0 ~ /"pluginId"[[:space:]]*:/) plugin_id = value($0)
            else if ($0 ~ /"enabled"[[:space:]]*:/) enabled = value($0)
            else if ($0 ~ /"activeGeneration"[[:space:]]*:/) active = value($0)
            else if ($0 ~ /"candidateGeneration"[[:space:]]*:/) candidate = value($0)
            else if ($0 ~ /"previousGeneration"[[:space:]]*:/) previous = value($0)
            if (closes > 0) {
                emit()
                plugin_id = ""
                enabled = active = candidate = previous = ""
            }
        }
        depth += opens - closes
    }
    END {
        if (matched != 1) print "  (current project is not registered)"
    }
' "$registry"
