#!/bin/bash
# Points the TV's Home button at Clear Home, and puts it back.
#   ./home.sh status    which app the Home button opens
#   ./home.sh use       make Clear Home the Home app (saves the original to .home-backup)
#   ./home.sh restore   undo everything 'use' did
#
# 'use' first asks Android nicely (set-home-activity). Google TV usually ignores
# that, so it then disables the stock launcher for the current user with
# 'pm disable-user'. That's reversible ('restore', or 'pm enable <package>'),
# needs no root, and doesn't uninstall anything.
set -euo pipefail
cd "$(dirname "$0")"

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
TV="${TV:-$(cat .tv 2>/dev/null || true)}"
if [ -z "$TV" ]; then echo "Run ./install.sh <TV_IP> first."; exit 1; fi
ME=dev.local.clearhome/.MainActivity
ME_PKG=dev.local.clearhome
tv() { "$ADB" -s "$TV" shell "$@" | tr -d '\r'; }

"$ADB" connect "$TV" >/dev/null 2>&1 || true

current_home() {
  tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1
}

case "${1:-status}" in
  status)
    echo "Home button opens: $(current_home)"
    ;;

  use)
    HOME_NOW=$(current_home)
    if [[ "$HOME_NOW" == $ME_PKG/* ]]; then echo "Already using Clear Home."; exit 0; fi
    [ -f .home-backup ] || echo "$HOME_NOW" > .home-backup
    echo "Original Home app saved: $HOME_NOW"

    tv cmd package set-home-activity "$ME" >/dev/null 2>&1 || true
    if [[ "$(current_home)" == $ME_PKG/* ]]; then echo "✓ Home button now opens Clear Home"; exit 0; fi

    # Still not us: disable whichever Home app is winning, one at a time, until we win.
    # Never touches the bare Settings fallback. Each change is recorded for 'restore'.
    for _ in 1 2 3; do
      WINNER=$(current_home)
      [[ "$WINNER" == $ME_PKG/* ]] && break
      PKG="${WINNER%%/*}"
      if [[ -z "$PKG" || "$PKG" == com.android.tv.settings || "$PKG" == com.android.settings || "$PKG" == android ]]; then
        echo "  stopping: next Home candidate is $WINNER"; break
      fi
      tv pm disable-user --user 0 "$PKG" >/dev/null && echo "  disabled $PKG"
      grep -qx "disabled:$PKG" .home-backup || echo "disabled:$PKG" >> .home-backup
      tv cmd package set-home-activity "$ME" >/dev/null 2>&1 || true
    done
    echo "Home button opens: $(current_home)"
    echo "Press Home on the remote to try it. Undo any time with: ./home.sh restore"
    ;;

  restore)
    if [ ! -f .home-backup ]; then echo "Nothing to restore."; exit 0; fi
    grep '^disabled:' .home-backup | cut -d: -f2 | while read -r PKG; do
      tv pm enable "$PKG" >/dev/null && echo "  re-enabled $PKG"
    done
    ORIG=$(head -1 .home-backup)
    tv cmd package set-home-activity "$ORIG" >/dev/null 2>&1 || true
    rm .home-backup
    echo "Home button opens: $(current_home)"
    ;;

  *) echo "Usage: ./home.sh status|use|restore"; exit 1 ;;
esac
