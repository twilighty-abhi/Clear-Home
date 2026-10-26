"""Pull the per-photo metadata files out of a Google Photos Takeout export.

Usage: ./takeout-sidecars.sh ~/Downloads/takeout-*.zip   (zips or unzipped folders)

Google Photos Takeout puts a JSON file next to each photo (IMG_1.jpg.json or
IMG_1.jpg.supplemental-metadata.json) with the location Google Photos knows,
the description and the time taken. This collects just those files into one
folder; drag its contents into your "TV Photos" Drive folder and the TV matches
them to the photos by name.

Only JSON is read from the archives; photos and anything else are left alone.
"""
import io
import json
import os
import subprocess
import sys
import zipfile

OUT_NAME = "TV Photos - Takeout info"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = os.environ.get("ADB", os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
TV_DIR = "/sdcard/Android/data/dev.local.clearhome/files/takeout"


def send_to_tv(out_dir):
    """Copies the info files straight to Clear Home on the TV over adb. Returns True on success."""
    try:
        with open(os.path.join(ROOT, ".tv")) as f:
            serial = f.read().strip()
    except OSError:
        return False
    subprocess.run([ADB, "connect", serial], capture_output=True)
    if subprocess.run([ADB, "-s", serial, "get-state"], capture_output=True, text=True).stdout.strip() != "device":
        return False
    subprocess.run([ADB, "-s", serial, "shell", f"rm -rf {TV_DIR} && mkdir -p {TV_DIR}"], capture_output=True)
    r = subprocess.run([ADB, "-s", serial, "push", out_dir + "/.", TV_DIR], capture_output=True, text=True)
    return r.returncode == 0


def is_sidecar(data):
    return isinstance(data, dict) and "title" in data and ("photoTakenTime" in data or "geoData" in data)


def has_geo(data):
    for key in ("geoData", "geoDataExif"):
        g = data.get(key) or {}
        if g.get("latitude") or g.get("longitude"):
            return True
    return False


def unique_path(out_dir, name):
    base = os.path.basename(name)  # never trust archive paths
    path = os.path.join(out_dir, base)
    n = 1
    while os.path.exists(path):
        stem, ext = os.path.splitext(base)
        path = os.path.join(out_dir, f"{stem} [{n}]{ext}")
        n += 1
    return path


def zip_sidecars(z, depth=0):
    """JSON entries of a zip, looking inside nested zips (Drive wraps Takeout zips in another zip)."""
    for info in z.infolist():
        lower = info.filename.lower()
        if lower.endswith(".json") and info.file_size < 1_000_000:
            yield info.filename, z.read(info)
        elif lower.endswith(".zip") and depth < 2:
            with z.open(info) as fh:
                data = io.BytesIO(fh.read())
            if zipfile.is_zipfile(data):
                with zipfile.ZipFile(data) as inner:
                    yield from zip_sidecars(inner, depth + 1)


def sources(args):
    for a in args:
        a = os.path.expanduser(a)
        if os.path.isdir(a):
            for root, _, files in os.walk(a):
                for f in files:
                    if f.lower().endswith(".json"):
                        p = os.path.join(root, f)
                        with open(p, "rb") as fh:
                            yield f, fh.read()
        elif zipfile.is_zipfile(a):
            with zipfile.ZipFile(a) as z:
                yield from zip_sidecars(z)
        else:
            print(f"Skipping {a}: not a zip or folder")


def main():
    if len(sys.argv) < 2:
        print(__doc__.strip().splitlines()[2])
        sys.exit(1)
    first = os.path.expanduser(sys.argv[1])
    out_dir = os.path.join(os.path.dirname(os.path.abspath(first)), OUT_NAME)
    os.makedirs(out_dir, exist_ok=True)

    total = geo = described = 0
    seen = set()  # Takeout often repeats the same file across its zips
    for name, raw in sources(sys.argv[1:]):
        try:
            data = json.loads(raw)
        except ValueError:
            continue
        if not is_sidecar(data):
            continue  # album metadata.json, print orders, etc.
        key = (data.get("title"), str((data.get("photoTakenTime") or {}).get("timestamp")))
        if key in seen:
            continue
        seen.add(key)
        with open(unique_path(out_dir, name), "wb") as fh:
            fh.write(raw)
        total += 1
        geo += has_geo(data)
        described += bool((data.get("description") or "").strip())

    if not total:
        print("No Google Photos metadata files found. Is this a Google Photos Takeout export?")
        sys.exit(1)
    print(f"✓ {total} photo info files → {out_dir}")
    print(f"  {geo} have a location, {described} have a description.")
    if send_to_tv(out_dir):
        print("✓ Sent to the TV. On the TV: Settings → Photos → Refresh now.")
    else:
        print("Couldn't reach the TV, so: drag that folder's contents into your TV Photos folder in Google Drive,")
        print("then on the TV: Settings → Photos → Refresh now.")


if __name__ == "__main__":
    main()
