#!/bin/bash
# Installs build/ClearHome.apk on the TV over the network and opens it.
#   ./install.sh 192.168.1.50     (the IP is remembered in .tv for next time)
set -euo pipefail
cd "$(dirname "$0")"

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
TV="${1:-$(cat .tv 2>/dev/null || true)}"
if [ -z "$TV" ]; then echo "Usage: ./install.sh <TV_IP>"; exit 1; fi
[[ "$TV" == *:* ]] || TV="$TV:5555"
echo "$TV" > .tv

[ -f build/ClearHome.apk ] || ./build.sh

"$ADB" connect "$TV" >/dev/null || true
for _ in $(seq 1 30); do
  STATE=$("$ADB" -s "$TV" get-state 2>&1 || true)
  [ "$STATE" = "device" ] && break
  if [[ "$STATE" == *unauthorized* ]]; then
    echo "Waiting: accept the 'Allow USB debugging?' prompt on the TV (tick 'Always allow')."
  fi
  sleep 2
  "$ADB" connect "$TV" >/dev/null 2>&1 || true
done
if [ "$("$ADB" -s "$TV" get-state 2>/dev/null)" != "device" ]; then
  echo "Couldn't connect to $TV. Check the IP, and that USB/network debugging is on."
  exit 1
fi

echo "• Installing on $TV"
"$ADB" -s "$TV" install -r build/ClearHome.apk
"$ADB" -s "$TV" shell am start -n dev.local.clearhome/.MainActivity >/dev/null
echo "✓ Clear Home is open on the TV"
