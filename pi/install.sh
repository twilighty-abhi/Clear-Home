#!/bin/bash
# Puts the flat server on a Raspberry Pi and points the TV at it.
#   pi/install.sh            install or update (PI=pi@raspberrypi.local, TV from .tv)
#   pi/install.sh --undo     TV runs the flat server itself again (the Pi copy is left alone)
#
# Runs as the Pi user (no sudo): the server starts at boot from that user's crontab and is
# restarted if it ever stops. Data lives in ~/clearhome/data.json on the Pi.
set -euo pipefail
cd "$(dirname "$0")/.."

PI="${PI:-pi@raspberrypi.local}"
PI_HOST="${PI#*@}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
TV="${TV:-$(cat .tv 2>/dev/null || true)}"
TV_IP="${TV%%:*}"
PORT=8642
HANDOFF=/sdcard/Android/data/dev.local.clearhome/files/flat-server.txt
[ -n "$TV" ] || { echo "Run ./install.sh <TV_IP> first."; exit 1; }

point_tv() { # $1: URL, or "" for the TV itself
  printf '%s' "$1" > /tmp/flat-server.txt
  "$ADB" -s "$TV" push /tmp/flat-server.txt "$HANDOFF" >/dev/null
  rm /tmp/flat-server.txt
  curl -s -m 5 -X POST "http://$TV_IP:$PORT/api/changed" >/dev/null || true
}

if [ "${1:-}" = "--undo" ]; then
  point_tv ""
  echo "✓ The TV runs the flat app itself again (http://$TV_IP:$PORT)"
  exit 0
fi

# 1. Server files
mkdir -p pi/web
cp app/assets/web/index.html app/assets/web/icon.svg app/assets/web/manifest.webmanifest app/assets/fonts/Outfit.ttf pi/web/
ssh "$PI" 'mkdir -p ~/clearhome/app/web'
scp -q pi/flat_server.py "$PI":clearhome/app/
scp -q pi/web/* "$PI":clearhome/app/web/

# 2. First install: bring over the TV's data
if ! ssh "$PI" 'test -s ~/clearhome/data.json'; then
  echo "Note: no data on the Pi yet; the flat starts empty (set it up from the web app)"
fi

# 3. Start at boot, restart if it stops; (re)start now
ssh "$PI" "cat > ~/clearhome/run.sh" <<EOF
#!/bin/sh
# Keeps the Clear Home flat server running (started at boot from crontab), plus the
# Cloudflare tunnel for the public address once ~/clearhome/tunnel-token exists.
cd ~/clearhome/app
if [ -x ~/clearhome/bin/cloudflared ]; then
  ( while true; do
      [ -s ~/clearhome/tunnel-token ] && ~/clearhome/bin/cloudflared tunnel --no-autoupdate run \\
        --token-file ~/clearhome/tunnel-token >> ~/clearhome/tunnel.log 2>&1
      sleep 30
    done ) &
fi
while true; do
  python3 flat_server.py --tv $TV_IP --port $PORT >> ~/clearhome/server.log 2>&1
  sleep 5
done
EOF
# (A separate script, so its own command line doesn't match the pkill patterns.)
ssh "$PI" "cat > ~/clearhome/restart.sh" <<'EOS'
#!/bin/sh
chmod +x ~/clearhome/run.sh
(crontab -l 2>/dev/null | grep -v clearhome/run.sh; echo "@reboot $HOME/clearhome/run.sh") | crontab -
pkill -f "clearhome/run.sh" 2>/dev/null
pkill -f "flat_server.py --tv" 2>/dev/null
pkill -f "cloudflared tunnel" 2>/dev/null
nohup "$HOME/clearhome/run.sh" >/dev/null 2>&1 &
sleep 3
EOS
ssh "$PI" 'sh ~/clearhome/restart.sh'
curl -sf -m 5 "http://$PI_HOST:$PORT/api/state" >/dev/null || { echo "✗ The Pi server didn't start; see ~/clearhome/server.log on the Pi"; exit 1; }

# 4. The TV reads from the Pi; phones opening the TV's address are sent there
point_tv "http://$PI_HOST:$PORT"
echo "✓ Flat app on http://$PI_HOST:$PORT (undo: pi/install.sh --undo)"
