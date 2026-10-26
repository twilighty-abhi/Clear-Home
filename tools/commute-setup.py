"""Send the commute route and TomTom key to Clear Home on the TV (reads secrets/commute.json)."""
import json
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = os.environ.get("ADB", os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
TV_PATH = "/sdcard/Android/data/dev.local.clearhome/files/commute.json"


def fail(msg):
    print("✗ " + msg)
    sys.exit(1)


def main():
    with open(os.path.join(ROOT, "secrets", "commute.json")) as f:
        cfg = json.load(f)
    # A Mapbox public token (pk.…) may have been pasted into the TomTom line; treat it as Mapbox.
    if str(cfg.get("tomtom_key", "")).startswith("pk."):
        cfg["mapbox_token"] = cfg.pop("tomtom_key")
    has_key = lambda k: cfg.get(k) and not str(cfg[k]).startswith("PASTE_")
    if not has_key("mapbox_token") and not has_key("tomtom_key"):
        fail("Add a Mapbox token (or TomTom key) to secrets/commute.json first.")
    ends = ("area",) if "area" in cfg else ("from", "to")
    for end in ends:
        p = cfg.get(end) or {}
        if not p.get("lat") or not p.get("lon"):
            fail(f"Set the \"{end}\" latitude/longitude in secrets/commute.json first.")
    with open(os.path.join(ROOT, ".tv")) as f:
        serial = f.read().strip()
    subprocess.run([ADB, "connect", serial], capture_output=True)
    fd, tmp = tempfile.mkstemp(prefix="clearhome-", suffix=".json")
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(cfg, f)
        r = subprocess.run([ADB, "-s", serial, "push", tmp, TV_PATH], capture_output=True, text=True)
        if r.returncode != 0:
            fail("Couldn't copy to the TV: " + (r.stderr or r.stdout).strip())
    finally:
        os.remove(tmp)
    what = (f"traffic map near {cfg['area'].get('name')}" if cfg.get("mapbox_token") else
            f"traffic near {cfg['area'].get('name')}") if "area" in cfg else \
        f"{cfg['from'].get('name')} → {cfg['to'].get('name')}"
    print(f"✓ Sent to the TV: {what}. With Clear Home open, the traffic card appears within a minute.")


if __name__ == "__main__":
    main()
