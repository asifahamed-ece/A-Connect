#!/usr/bin/env bash
# Installs the user-level systemd timer that clears stale previews.
#
# The unit files are templates: systemd wants absolute paths and cannot know
# where this checkout lives, so the placeholders are filled in with the real
# node and project paths at install time. Run it again after moving the project.
#
#   bash scripts/install-preview-cleanup.sh [--days N]
set -euo pipefail

DAYS=30
while [[ $# -gt 0 ]]; do
  case "$1" in
    --days) DAYS="${2:?--days needs a number}"; shift 2 ;;
    --days=*) DAYS="${1#*=}"; shift ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NODE="$(command -v node || true)"
if [[ -z "$NODE" ]]; then
  echo "node was not found on PATH; the timer needs it to run the sweep" >&2
  exit 1
fi

UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
mkdir -p "$UNIT_DIR"

render() {
  local src="$1" dest="$2"
  sed \
    -e "s|__PROJECT__|${PROJECT}|g" \
    -e "s|__NODE__|${NODE}|g" \
    "$src" > "$dest"
}

render "$PROJECT/scripts/systemd/aplus-preview-cleanup.service" \
       "$UNIT_DIR/aplus-preview-cleanup.service"
render "$PROJECT/scripts/systemd/aplus-preview-cleanup.timer" \
       "$UNIT_DIR/aplus-preview-cleanup.timer"

# The service's age threshold accepts a custom --days value. The timer checks
# daily so any entry is cleaned up shortly after its age exceeds that window.
sed -i "s|--days 30|--days ${DAYS}|; s|APLUS_PREVIEW_DAYS=30|APLUS_PREVIEW_DAYS=${DAYS}|" \
  "$UNIT_DIR/aplus-preview-cleanup.service"

systemctl --user daemon-reload
systemctl --user enable --now aplus-preview-cleanup.timer

echo "installed: $UNIT_DIR/aplus-preview-cleanup.timer"
echo "clears previews untouched for ${DAYS} days"
systemctl --user list-timers aplus-preview-cleanup.timer --no-pager
