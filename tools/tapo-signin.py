"""Send the Tapo login and plug names to Clear Home on the TV.

Reads secrets/tapo.json and copies it to the TV over adb. The TV moves it into
private storage and deletes the copied file; nothing else keeps a copy.
"""
import json
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = os.path.join(ROOT, "secrets", "tapo.json")
ADB = os.environ.get("ADB", os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
TV_PATH = "/sdcard/Android/data/dev.local.clearhome/files/tapo.json"


def fail(msg):
    print("✗ " + msg)
    sys.exit(1)


def main():
    try:
        with open(CONFIG) as f:
            cfg = json.load(f)
    except (OSError, ValueError) as e:
        fail(f"Couldn't read secrets/tapo.json ({e}).")
    if not cfg.get("plugs"):
        fail("secrets/tapo.json has no plugs listed.")
    for p in cfg["plugs"]:  # each plug's own login, or a shared one at the top
        email = p.get("email") or cfg.get("email", "")
        password = p.get("password") or cfg.get("password", "")
        if "@" not in email or not password or password.startswith("PASTE_") or email.startswith("PASTE_"):
            fail(f"Fill in the Tapo email and password for \"{p.get('name', '?')}\" in secrets/tapo.json first.")
    try:
        with open(os.path.join(ROOT, ".tv")) as f:
            serial = f.read().strip()
    except OSError:
        fail("No TV address yet. Run ./install.sh <TV_IP> first.")
    subprocess.run([ADB, "connect", serial], capture_output=True)
    if subprocess.run([ADB, "-s", serial, "get-state"], capture_output=True, text=True).stdout.strip() != "device":
        fail(f"Can't reach the TV at {serial}.")

    fd, tmp = tempfile.mkstemp(prefix="clearhome-", suffix=".json")
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(cfg, f)
        r = subprocess.run([ADB, "-s", serial, "push", tmp, TV_PATH], capture_output=True, text=True)
        if r.returncode != 0:
            fail("Couldn't copy to the TV: " + (r.stderr or r.stdout).strip())
    finally:
        os.remove(tmp)
    names = ", ".join(p.get("name", "?") for p in cfg["plugs"])
    print(f"✓ Sent to the TV ({names}). Open Clear Home; the AC cards appear within a minute.")


if __name__ == "__main__":
    main()
