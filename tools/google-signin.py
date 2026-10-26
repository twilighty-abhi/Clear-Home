"""One-time Google Drive sign-in for Clear Home's photo background, run on a computer.

Google only allows Drive's app-created-files permission in the TV sign-in flow, so
this does a normal browser sign-in here (loopback redirect + PKCE) with read-only
Drive access, asks which folder to show, then copies the sign-in and folder choice
to the TV over adb. The TV imports it into private storage and deletes the copied
file. Nothing is kept on this computer.

Needs secrets/google-desktop.json: the "Desktop app" OAuth client downloaded from
Google Cloud (Clients -> your Desktop client -> Download JSON), or a file with
{"client_id": "...", "client_secret": "..."}.
"""
import base64
import hashlib
import http.server
import json
import os
import re
import secrets
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request
import webbrowser

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLIENT_FILE = os.path.join(ROOT, "secrets", "google-desktop.json")
ADB = os.environ.get("ADB", os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
SCOPE = "https://www.googleapis.com/auth/drive.readonly"
DRIVE = "https://www.googleapis.com/drive/v3"
FOLDER_MIME = "application/vnd.google-apps.folder"
TV_PATH = "/sdcard/Android/data/dev.local.clearhome/files/google-signin.json"


def fail(msg):
    print("✗ " + msg)
    sys.exit(1)


def load_client():
    if not os.path.exists(CLIENT_FILE):
        fail(f"Missing {os.path.relpath(CLIENT_FILE, ROOT)}. Download your Desktop OAuth client JSON from "
             "Google Cloud and save it there.")
    with open(CLIENT_FILE) as f:
        data = json.load(f)
    data = data.get("installed", data)
    cid, secret = data.get("client_id", ""), data.get("client_secret", "")
    if not cid.endswith(".apps.googleusercontent.com") or not secret or secret.startswith("PASTE_"):
        fail(f"{os.path.relpath(CLIENT_FILE, ROOT)} doesn't contain a client ID and secret yet.")
    return cid, secret


def tv_serial():
    try:
        with open(os.path.join(ROOT, ".tv")) as f:
            return f.read().strip()
    except OSError:
        fail("No TV address yet. Run ./install.sh <TV_IP> first.")


def adb(serial, *args, check=True):
    r = subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        fail(f"adb {' '.join(args[:2])} failed: {(r.stderr or r.stdout).strip()}")
    return r.stdout


def browser_signin(cid, secret):
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(16)
    result = {}

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            if q.get("state", [""])[0] != state:
                self.send_response(400)
                self.end_headers()
                return
            result.update({k: v[0] for k, v in q.items()})
            ok = "code" in result
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            msg = "Signed in. You can close this tab and go back to the TV." if ok else \
                "Sign-in didn't complete. Check the terminal."
            self.wfile.write(f"<html><body style='font:20px system-ui;padding:40px'>{msg}</body></html>".encode())

        def log_message(self, *a):
            pass

    server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
    redirect = f"http://127.0.0.1:{server.server_port}/"
    url = "https://accounts.google.com/o/oauth2/v2/auth?" + urllib.parse.urlencode({
        "client_id": cid, "redirect_uri": redirect, "response_type": "code", "scope": SCOPE,
        "code_challenge": challenge, "code_challenge_method": "S256",
        "access_type": "offline", "prompt": "consent", "state": state,
    })
    print("Opening Google sign-in in your browser…")
    print("(If it doesn't open, paste this into your browser:)\n" + url + "\n")
    webbrowser.open(url)
    while not result:
        server.handle_request()
    server.server_close()

    if "error" in result:
        fail("Google sign-in returned: " + result["error"])
    body = urllib.parse.urlencode({
        "code": result["code"], "client_id": cid, "client_secret": secret, "redirect_uri": redirect,
        "grant_type": "authorization_code", "code_verifier": verifier,
    }).encode()
    try:
        with urllib.request.urlopen("https://oauth2.googleapis.com/token", body) as r:
            tokens = json.load(r)
    except urllib.error.HTTPError as e:
        fail("Token exchange failed: " + e.read().decode(errors="replace"))
    if SCOPE not in tokens.get("scope", ""):
        fail("Google Drive access wasn't granted. Run this again and tick the Google Drive box.")
    if not tokens.get("refresh_token"):
        fail("Google didn't return a long-lived sign-in. Run this again.")
    return tokens["refresh_token"], tokens["access_token"]


def drive_get(access, path, **params):
    url = DRIVE + path + ("?" + urllib.parse.urlencode(params) if params else "")
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + access})
    try:
        with urllib.request.urlopen(req) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        if e.code == 403 and b"accessNotConfigured" in e.read():
            fail("The Google Drive API isn't enabled in your Cloud project. Enable it, then run this again.")
        return None


def choose_folder(access):
    answer = input("\nWhich Drive folder should the TV show? Name or link [TV Photos]: ").strip() or "TV Photos"
    folder = None
    m = re.search(r"/folders/([A-Za-z0-9_-]+)", answer) or re.search(r"[?&]id=([A-Za-z0-9_-]+)", answer)
    if m:
        folder = drive_get(access, "/files/" + m.group(1), fields="id,name,mimeType", supportsAllDrives="true")
        if not folder or folder.get("mimeType") != FOLDER_MIME:
            fail("That link isn't a Drive folder this account can see.")
    else:
        name = answer.replace("\\", "\\\\").replace("'", "\\'")
        found = drive_get(access, "/files", q=f"name = '{name}' and mimeType = '{FOLDER_MIME}' and trashed = false",
                          fields="files(id,name,modifiedTime)", orderBy="modifiedTime desc",
                          supportsAllDrives="true", includeItemsFromAllDrives="true") or {}
        folders = found.get("files", [])
        if not folders:
            fail(f"No folder called \"{answer}\" in your Drive. Create it (and add photos), then run this again.")
        if len(folders) > 1:
            print(f"Found {len(folders)} folders called \"{answer}\"; using the most recently changed one.")
        folder = folders[0]
    images = drive_get(access, "/files", q=f"'{folder['id']}' in parents and mimeType contains 'image/' and trashed = false",
                       fields="files(id)", pageSize="1000", supportsAllDrives="true",
                       includeItemsFromAllDrives="true") or {}
    print(f"Using \"{folder['name']}\" ({len(images.get('files', []))} photos so far).")
    return folder["id"], folder["name"]


def main():
    cid, secret = load_client()
    serial = tv_serial()
    subprocess.run([ADB, "connect", serial], capture_output=True)
    if adb(serial, "get-state", check=False).strip() != "device":
        fail(f"Can't reach the TV at {serial}. Is it on, with debugging enabled?")

    refresh, access = browser_signin(cid, secret)
    folder_id, folder_name = choose_folder(access)

    # Hand off via a private temp file that's removed straight after the push.
    fd, tmp = tempfile.mkstemp(prefix="clearhome-", suffix=".json")
    try:
        with os.fdopen(fd, "w") as f:
            json.dump({"client_id": cid, "client_secret": secret, "refresh_token": refresh,
                       "folder_id": folder_id, "folder_name": folder_name}, f)
        adb(serial, "push", tmp, TV_PATH)
    finally:
        os.remove(tmp)

    print("✓ Done. The TV is downloading the photos now; see Settings → Photos on the TV.")


if __name__ == "__main__":
    main()
