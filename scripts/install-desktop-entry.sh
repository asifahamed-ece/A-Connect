#!/usr/bin/env bash
# Adds A+ Connect to the application launcher with its icon, for this user.
#
# KDE (and GNOME) take a window's icon from the .desktop file whose name
# matches the window's app id, not from the window itself. Without this file
# the task manager shows a generic icon even though the window sets one.
#
#   scripts/install-desktop-entry.sh            install / update
#   scripts/install-desktop-entry.sh --remove   uninstall
set -euo pipefail

APP_ID="a-plus-connect"
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
DESKTOP_FILE="$DATA_HOME/applications/$APP_ID.desktop"
ICON_SRC="$APP_DIR/assets/icons"
SIZES="16 22 24 32 48 64 128 256 512"

refresh_caches() {
  command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database -q "$DATA_HOME/applications" || true
  command -v gtk-update-icon-cache >/dev/null 2>&1 && gtk-update-icon-cache -q -t "$DATA_HOME/icons/hicolor" || true
  for k in kbuildsycoca6 kbuildsycoca5; do
    if command -v "$k" >/dev/null 2>&1; then "$k" --noincremental >/dev/null 2>&1 || true; break; fi
  done
}

if [[ "${1:-}" == "--remove" ]]; then
  rm -f "$DESKTOP_FILE"
  for s in $SIZES; do rm -f "$DATA_HOME/icons/hicolor/${s}x${s}/apps/$APP_ID.png"; done
  refresh_caches
  echo "Removed A+ Connect from the launcher."
  exit 0
fi

if [[ ! -f "$ICON_SRC/256x256.png" ]]; then
  echo "Icons not found in $ICON_SRC" >&2
  exit 1
fi

for s in $SIZES; do
  install -Dm644 "$ICON_SRC/${s}x${s}.png" "$DATA_HOME/icons/hicolor/${s}x${s}/apps/$APP_ID.png"
done

# Prefer a system electron on PATH (as `npm start` does); fall back to npm.
if command -v electron >/dev/null 2>&1; then
  EXEC="electron \"$APP_DIR\" --disable-vulkan"
else
  EXEC="sh -c 'cd \"$APP_DIR\" && npm start'"
fi

mkdir -p "$(dirname "$DESKTOP_FILE")"
sed -e "s|@APP_DIR@|$APP_DIR|g" -e "s|@EXEC@|$EXEC|g" "$APP_DIR/scripts/a-plus-connect.desktop.in" > "$DESKTOP_FILE"
chmod 644 "$DESKTOP_FILE"
refresh_caches

echo "Installed: $DESKTOP_FILE"
echo "A+ Connect is in the launcher now. Restart the app once so the task manager picks up the icon."
