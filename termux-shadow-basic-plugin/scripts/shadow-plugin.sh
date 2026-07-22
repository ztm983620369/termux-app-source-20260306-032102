#!/data/data/com.termux/files/usr/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SCRIPT_DIR=$ROOT_DIR/scripts

usage() {
    cat <<'EOF'
Termux Shadow plugin CLI

Usage:
  ./shadow-plugin new <slug> [display-name] [options]
  ./shadow-plugin doctor [--project-only] [--full]
  ./shadow-plugin build [--version-code N] [--version-name NAME]
  ./shadow-plugin publish [--version-code N] [--version-name NAME] [--no-wait]
  ./shadow-plugin upgrade <version-code> <version-name>
  ./shadow-plugin status [--all] [--wait] [--timeout SECONDS]
  ./shadow-plugin config
  ./shadow-plugin clean

Examples:
  ./shadow-plugin new notes "Notes" --publish
  ./shadow-plugin doctor
  ./shadow-plugin publish
  ./shadow-plugin upgrade 2 2.0.0
  ./shadow-plugin status --all
EOF
}

command=${1:-help}
if [ "$#" -gt 0 ]; then
    shift
fi

case "$command" in
    new)
        exec sh "$SCRIPT_DIR/new-plugin.sh" "$@"
        ;;
    doctor)
        exec sh "$SCRIPT_DIR/doctor.sh" "$@"
        ;;
    build)
        exec sh "$SCRIPT_DIR/build-debug.sh" --validate-only "$@"
        ;;
    publish)
        printf 'publish requires the installed native shadow-plugin; the shell fallback is build-only\n' >&2
        exit 69
        ;;
    upgrade)
        if [ "$#" -ne 2 ]; then
            printf 'upgrade requires VERSION_CODE and VERSION_NAME\n' >&2
            exit 2
        fi
        printf 'upgrade requires the installed native shadow-plugin; the shell fallback is build-only\n' >&2
        exit 69
        ;;
    status|list)
        if [ "$command" = list ]; then
            set -- --all "$@"
        fi
        exec sh "$SCRIPT_DIR/status.sh" "$@"
        ;;
    config)
        exec sed -n '1,240p' "$ROOT_DIR/shadow-plugin.properties"
        ;;
    clean)
        exec sh "$SCRIPT_DIR/clean.sh" "$@"
        ;;
    help|-h|--help)
        usage
        ;;
    *)
        printf 'Unknown command: %s\n\n' "$command" >&2
        usage >&2
        exit 2
        ;;
esac
