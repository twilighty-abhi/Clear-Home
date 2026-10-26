#!/usr/bin/env python3
"""
Clear Home flat server for a Raspberry Pi: the phone web app and the flat's shared state
(notes, flatmates and who's home, chores, shopping, reminders, guest Wi-Fi).

The TV reads its dashboard data from here and sends today's TV time; photos stay on the TV,
so the photo endpoints are passed through to it. Standard library only.

    python3 flat_server.py --tv 192.168.1.3 [--port 8642] [--data ~/clearhome/data.json]

Who can do what:
  - members: a linked phone (cookie), someone signed in through Cloudflare Access (the tunnel
    passes their email), or the TV itself. They get the whole app.
  - guests: anyone else on the Wi-Fi (e.g. a visitor scanning the sticky note's QR). They can
    leave a note, or link their phone with the flat PIN if they live here.
Requests from outside the home Wi-Fi never count towards who's home.

Who's home: each flatmate taps their name in the web app once, which links that phone's
address (and its Wi-Fi hardware address, from the Pi's neighbour table). Every minute the Pi
looks for those phones: a ping answer, a refused TCP connection, or a fresh neighbour entry
means the phone is on the Wi-Fi. Someone counts as home if seen in the last 20 minutes.
"""

import argparse
import datetime as dt
import hashlib
import hmac
import ipaddress
import http.server
import json
import os
import re
import socket
import subprocess
import threading
import time
import urllib.parse
import urllib.request
import uuid

PORT = 8642
MAX_NOTES, MAX_LEN = 6, 240
HOME_WINDOW = 20 * 60
COLOURS = ["#f2b705", "#4cc3a5", "#e86f6f", "#6aa8ff", "#c58af9", "#ff9f5a"]
HERE = os.path.dirname(os.path.abspath(__file__))
WEB = os.path.join(HERE, "web")

args = None
lock = threading.RLock()
data = {}


# ---------- storage ----------

def load():
    global data
    try:
        with open(args.data) as f:
            data = json.load(f)
    except (OSError, ValueError):
        data = {}
    for k, empty in (("notes", []), ("flatmates", []), ("chores", []), ("choresDone", {}), ("choreTakes", {}),
                     ("shopping", []), ("reminders", []), ("guest", {}), ("tv", {"allowed": False}),
                     ("pin", ""), ("emails", {}), ("timers", []), ("clip", {})):
        data.setdefault(k, empty)


def save():
    tmp = args.data + ".tmp"
    with open(tmp, "w") as f:
        json.dump(data, f, ensure_ascii=False)
    os.replace(tmp, args.data)  # never leaves a half-written file behind
    global _last_save
    _last_save = time.time()


_last_save = 0.0


def save_soon(important):
    """For refreshes that only bump "last seen": write now if it matters, else at most every 5 minutes."""
    global _last_save
    if important or time.time() - _last_save > 300:
        save()


def now_ms():
    return int(time.time() * 1000)


def short_id():
    return uuid.uuid4().hex[:8]


# ---------- state ----------

def initials(name):
    n = (name or "").strip()
    if not n:
        return "?"
    parts = n.split()
    if len(parts) > 1:
        return (parts[0][0] + parts[1][0]).upper()
    return n.upper() if len(n) == 1 else n[0].upper() + n[1].lower()


def is_home(f):
    return time.time() - f.get("seen", 0) / 1000 < HOME_WINDOW


# ---------- timers ----------

def timers():
    """Running, paused and just-finished timers. Finished ones linger 10 minutes unless dismissed."""
    now = now_ms()
    data["timers"] = [t for t in data["timers"] if t.get("paused") or now - t.get("ends", now) < 10 * 60_000]
    out = []
    for t in data["timers"]:
        left = t.get("left", 0) if t.get("paused") else max(0, t["ends"] - now)
        out.append(dict(t, left=left, done=not t.get("paused") and left == 0))
    return out


def find_timer(tid):
    return next((t for t in data["timers"] if t["id"] == tid), None)


def guest_state():
    return {"role": "guest", "pinSet": bool(data["pin"]), "notes": [], "flatmates": []}


def state(me=""):
    with lock:
        fms = data["flatmates"]
        return {
            "notes": [n for n in data["notes"] if n.get("until", 0) > now_ms()],
            "flatmates": [{"id": f["id"], "name": f.get("name", ""), "colour": f.get("colour", COLOURS[i % len(COLOURS)]),
                           "emoji": f.get("emoji", ""), "initials": initials(f.get("name")),
                           "linked": bool(f.get("ip")), "home": is_home(f)} for i, f in enumerate(fms)],
            "chores": chores_today(fms),
            "shopping": data["shopping"],
            "reminders": reminders_upcoming(),
            "guest": guest(),
            "tv": data["tv"],
            "me": me,
            "role": "member",
            "pinSet": bool(data["pin"]),
            "timers": timers(),
            "clip": data["clip"],
        }


def guest():
    g = dict(data["guest"])
    g["on"] = g.get("until", 0) > now_ms() and bool(g.get("ssid"))
    return g


def period(every):
    offset = dt.datetime.now().astimezone().utcoffset().total_seconds()
    day = int((time.time() + offset) // 86400)
    return (day + 3) // 7 if every == "week" else day  # weeks start on Monday


def index_of(fms, fid):
    for i, f in enumerate(fms):
        if f["id"] == fid:
            return i
    return -1


def chores_today(fms):
    """Whose turn it is: rotating through the flatmates in order, or always one person ("owner");
    anyone can take over the current turn, which leaves the rotation itself unchanged."""
    out = []
    for i, c in enumerate(data["chores"]):
        every = c.get("every", "day")
        p = period(every)
        key = f"{c['id']}:{p}"
        owner = index_of(fms, c.get("owner", ""))
        idx, nxt = -1, -1
        if owner >= 0:
            idx = owner
        elif fms:
            idx = (p + c.get("offset", i)) % len(fms)
            nxt = (idx + 1) % len(fms)
        taker = index_of(fms, data["choreTakes"].get(key, ""))
        swapped = ""
        if taker >= 0 and taker != idx:
            swapped = fms[idx]["name"] if idx >= 0 else ""
            idx = taker
        out.append({"id": c["id"], "name": c.get("name", ""), "every": every,
                    "owner": fms[owner]["id"] if owner >= 0 else "",
                    "assignee": fms[idx]["name"] if idx >= 0 else "",
                    "assigneeId": fms[idx]["id"] if idx >= 0 else "",
                    "swappedFrom": swapped,
                    "next": fms[nxt]["name"] if nxt >= 0 else "",
                    "done": bool(data["choresDone"].get(key))})
    return out


def reminders_upcoming():
    today = dt.date.today()
    out = []
    for r in data["reminders"]:
        try:
            y, m, d = (int(x) for x in r["date"].split("-"))
        except (KeyError, ValueError):
            continue
        clamp = lambda yy, mm: min(d, [31, 29 if yy % 4 == 0 and (yy % 100 or yy % 400 == 0) else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][mm - 1])
        if r.get("repeat") == "month":
            nxt = dt.date(today.year, today.month, clamp(today.year, today.month))
            if nxt < today:
                yy, mm = (today.year + 1, 1) if today.month == 12 else (today.year, today.month + 1)
                nxt = dt.date(yy, mm, clamp(yy, mm))
        else:
            nxt = dt.date(y, m, clamp(y, m))
            if nxt < today:
                continue  # past one-off reminders drop off
        out.append(dict(r, next=nxt.isoformat(), daysLeft=(nxt - today).days))
    return sorted(out, key=lambda r: r["daysLeft"])


def prune(by_period):
    """Drop "chore:period" entries older than ~2 months."""
    cutoff = period("day") - 60
    keep = {}
    for k, v in by_period.items():
        try:
            p = int(k.rsplit(":", 1)[1])
        except (IndexError, ValueError):
            continue
        if p >= cutoff or (10_000 < p < 100_000 and p * 7 >= cutoff):
            keep[k] = v
    return keep


def name_of(fid):
    for f in data["flatmates"]:
        if f["id"] == fid:
            return f.get("name", "")
    return ""


def find(items, iid):
    return next((x for x in items if x.get("id") == iid), None)


# ---------- who's home ----------

def neighbours():
    """{ip: mac} for complete entries in the Pi's neighbour (ARP) table."""
    out = {}
    try:
        with open("/proc/net/arp") as f:
            for line in f.readlines()[1:]:
                p = line.split()
                if len(p) >= 4 and p[2] != "0x0" and p[3] != "00:00:00:00:00:00":
                    out[p[0]] = p[3].lower()
    except OSError:
        pass
    return out


def alive(ip):
    """A phone is on the Wi-Fi if it answers a ping, or refuses (rather than ignores) a TCP connection."""
    try:
        if subprocess.run(["ping", "-c", "1", "-W", "1", ip], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
            return True
    except OSError:
        pass
    for port in (62078, 7000, 9):  # 62078: iPhone sync service; the others are usually refused
        s = socket.socket()
        s.settimeout(0.8)
        try:
            s.connect((ip, port))
            return True
        except ConnectionRefusedError:
            return True
        except OSError:
            pass
        finally:
            s.close()
    return False


def probe_presence():
    with lock:
        phones = [(f["id"], f.get("ip", ""), f.get("mac", "")) for f in data["flatmates"] if f.get("ip") or f.get("mac")]
    seen = {}
    for fid, ip, mac in phones:
        if ip and alive(ip):
            seen[fid] = ip
    table = neighbours()
    by_mac = {m: ip for ip, m in table.items()}
    for fid, ip, mac in phones:
        if fid not in seen and mac and mac in by_mac and alive(by_mac[mac]):
            seen[fid] = by_mac[mac]  # same phone, new address from the router
    if not seen:
        return False
    before = [is_home(f) for f in data["flatmates"]]
    with lock:
        for f in data["flatmates"]:
            if f["id"] in seen:
                f["seen"] = now_ms()
                f["ip"] = seen[f["id"]]
                f["mac"] = table.get(f["ip"], f.get("mac", ""))
        save()
        return before != [is_home(f) for f in data["flatmates"]]


def presence_loop():
    while True:
        try:
            if probe_presence():
                tell_tv()
        except Exception as e:  # keep checking whatever happens
            print("presence:", e, flush=True)
        time.sleep(60)


# ---------- the TV ----------

def tv_url(path):
    return f"http://{args.tv}:{PORT}{path}"


def tell_tv():
    """Ask the TV to refresh its dashboard now (it also checks every 20 seconds)."""
    def go():
        try:
            urllib.request.urlopen(urllib.request.Request(tv_url("/api/changed"), data=b"", method="POST"), timeout=3).read()
        except Exception:
            pass
    threading.Thread(target=go, daemon=True).start()


# ---------- mutations ----------

def add_note(text, frm, hours):
    text = (text or "").strip()[:MAX_LEN]
    if not text:
        return False
    hours = max(1, min(48, hours))
    n = {"id": str(uuid.uuid4()), "text": text, "from": (frm or "").strip(), "at": now_ms(), "until": now_ms() + hours * 3_600_000}
    live = [x for x in data["notes"] if x.get("until", 0) > now_ms()]
    data["notes"] = [n] + live[:MAX_NOTES - 1]
    return True


def update_flatmate(fid, form):
    f = find(data["flatmates"], fid)
    if not f:
        return False
    name = form.get("name", "").strip()
    if name and len(name) <= 24:
        f["name"] = name
    if "emoji" in form:
        f["emoji"] = form["emoji"].strip()[:8]
    if re.fullmatch(r"#[0-9a-fA-F]{6}", form.get("colour", "")):
        f["colour"] = form["colour"]
    return True


def claim(fid, ip):
    f = find(data["flatmates"], fid)
    if not f:
        return False
    for o in data["flatmates"]:
        if o is not f and o.get("ip") == ip:
            o.pop("ip", None)  # a phone belongs to one person
            o.pop("mac", None)
    if is_lan(ip):
        f.update(ip=ip, seen=now_ms(), mac=neighbours().get(ip, ""))
    return True


def touch(fid, ip):
    """A linked phone opening the app refreshes its address. Returns True if they were away."""
    f = find(data["flatmates"], fid)
    if not f:
        return False
    if not is_lan(ip):
        return False  # opening the app from outside doesn't mean you're home
    was_away = not is_home(f)
    f.update(ip=ip, seen=now_ms())
    mac = neighbours().get(ip)
    if mac:
        f["mac"] = mac
    return was_away


def is_lan(ip):
    try:
        a = ipaddress.ip_address(ip)
        return a.is_private and not a.is_loopback
    except ValueError:
        return False


def pin_hash(pin, salt):
    return hashlib.pbkdf2_hmac("sha256", pin.encode(), salt.encode(), 100_000).hex()


def pin_ok(pin):
    if not data["pin"]:
        return True  # no PIN set yet: linking stays open
    salt, h = data["pin"].split("$", 1)
    return hmac.compare_digest(pin_hash(pin or "", salt), h)


failures = {}  # ip -> [timestamps] of wrong PINs


def too_many_tries(ip):
    recent = [t for t in failures.get(ip, []) if time.time() - t < 600]
    failures[ip] = recent
    return len(recent) >= 8


GUEST_POSTS = ("/api/notes", "/notes", "/api/join", "/api/flatmates/claim")


def handle_post(q, form, me, ip):
    """Applies one change. Returns (ok, set_cookie)."""
    fl = data["flatmates"]
    if q in ("/api/notes", "/notes"):
        frm = form.get("from") or name_of(me)  # sign notes from linked phones
        return add_note(form.get("text"), frm, int(form.get("hours") or 3)), None
    if q in ("/api/notes/delete", "/notes/delete"):
        data["notes"] = [n for n in data["notes"] if n.get("id") != form.get("id")]
    elif q == "/api/flatmates/add":
        name = form.get("name", "").strip()
        if not name or len(name) > 24 or len(fl) >= 8:
            return False, None
        fl.append({"id": short_id(), "name": name, "colour": COLOURS[len(fl) % len(COLOURS)]})
    elif q == "/api/flatmates/delete":
        data["flatmates"] = [f for f in fl if f["id"] != form.get("id")]
    elif q == "/api/flatmates/update":
        return update_flatmate(form.get("id"), form), None
    elif q == "/api/flatmates/unlink":
        f = find(fl, form.get("id"))
        if not f:
            return False, None
        for k in ("ip", "mac", "seen"):
            f.pop(k, None)
    elif q == "/api/flatmates/claim":
        if not claim(form.get("id"), ip):
            return False, None
        return True, f"fm={form.get('id')}; Max-Age=31536000; Path=/; SameSite=Lax"
    elif q == "/api/timer/add":
        secs = int(float(form.get("seconds") or 0)) or int(float(form.get("minutes") or 0) * 60)
        if not 5 <= secs <= 24 * 3600 or len(data["timers"]) >= 6:
            return False, None
        data["timers"].append({"id": short_id(), "label": form.get("label", "").strip()[:24], "duration": secs * 1000,
                               "ends": now_ms() + secs * 1000, "paused": False, "important": form.get("important") == "1",
                               "by": name_of(me)})
    elif q in ("/api/timer/pause", "/api/timer/plus", "/api/timer/delete", "/api/timer/important"):
        t = find_timer(form.get("id"))
        if not t:
            return False, None
        if q.endswith("delete"):
            data["timers"].remove(t)
        elif q.endswith("important"):
            t["important"] = form.get("on") == "1"
        elif q.endswith("plus"):  # +1 minute (also revives a finished timer)
            if t.get("paused"):
                t["left"] += 60_000
            else:
                t["ends"] = max(t["ends"], now_ms()) + 60_000
            t["duration"] += 60_000
        elif t.get("paused"):  # resume
            t["ends"] = now_ms() + t.pop("left", 0)
            t["paused"] = False
        elif t["ends"] > now_ms():  # pause
            t["left"] = t["ends"] - now_ms()
            t["paused"] = True
    elif q == "/api/clip":  # text or a link for the TV; the remote's Web button types it in
        text = form.get("text", "").strip()[:2000]
        data["clip"] = {"text": text, "at": now_ms(), "by": name_of(me)} if text else {}
    elif q == "/api/pin":
        pin = form.get("pin", "")
        if pin == "":
            data["pin"] = ""
        elif re.fullmatch(r"\d{4,8}", pin):
            salt = uuid.uuid4().hex[:16]
            data["pin"] = salt + "$" + pin_hash(pin, salt)
        else:
            return False, None
    elif q == "/api/chores/add":
        name = form.get("name", "").strip()
        if not name or len(name) > 40:
            return False, None
        data["chores"].append({"id": short_id(), "name": name, "every": "week" if form.get("every") == "week" else "day",
                               "offset": len(data["chores"]), "owner": form.get("owner", "")})
    elif q == "/api/chores/update":
        c = find(data["chores"], form.get("id"))
        if not c:
            return False, None
        name = form.get("name", "").strip()
        if name and len(name) <= 40:
            c["name"] = name
        if "every" in form:
            c["every"] = "week" if form["every"] == "week" else "day"
        if "owner" in form:
            c["owner"] = form["owner"]
    elif q == "/api/chores/delete":
        data["chores"] = [c for c in data["chores"] if c["id"] != form.get("id")]
    elif q in ("/api/chores/done", "/api/chores/take"):
        c = find(data["chores"], form.get("id"))
        if not c or (q.endswith("take") and not me):
            return False, None
        key = f"{c['id']}:{period(c.get('every', 'day'))}"
        if q.endswith("done"):
            if data["choresDone"].pop(key, None) is None:
                data["choresDone"][key] = True
            data["choresDone"] = prune(data["choresDone"])
        else:  # "I'll do it"; taking it again hands it back
            if data["choreTakes"].get(key) == me:
                del data["choreTakes"][key]
            else:
                data["choreTakes"][key] = me
            data["choreTakes"] = prune(data["choreTakes"])
    elif q == "/api/shop/add":
        text = form.get("text", "").strip()[:60]
        if not text or len(data["shopping"]) >= 100:
            return False, None
        data["shopping"].append({"id": short_id(), "text": text, "by": name_of(me), "done": False, "at": now_ms()})
    elif q == "/api/shop/toggle":
        i = find(data["shopping"], form.get("id"))
        if i:
            i["done"] = not i.get("done")
    elif q == "/api/shop/delete":
        data["shopping"] = [i for i in data["shopping"] if i["id"] != form.get("id")]
    elif q == "/api/shop/clear":
        data["shopping"] = [i for i in data["shopping"] if not i.get("done")]
    elif q == "/api/remind/add":
        title, date = form.get("title", "").strip(), form.get("date", "")
        if not title or len(title) > 50 or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date) or len(data["reminders"]) >= 50:
            return False, None
        data["reminders"].append({"id": short_id(), "title": title, "date": date,
                                  "repeat": "month" if form.get("repeat") == "month" else "none", "by": name_of(me)})
    elif q == "/api/remind/delete":
        data["reminders"] = [r for r in data["reminders"] if r["id"] != form.get("id")]
    elif q == "/api/guest/save":
        ssid, pw, sec = form.get("ssid", "").strip(), form.get("pass", ""), form.get("security", "WPA")
        if not ssid or len(ssid) > 32 or len(pw) > 63:
            return False, None
        sec = sec if sec in ("WEP", "nopass") else "WPA"
        data["guest"].update({"ssid": ssid, "security": sec, "pass": "" if sec == "nopass" else pw})
    elif q == "/api/guest/show":
        h = max(0, min(48, int(form.get("hours") or 0)))
        data["guest"].update(until=now_ms() + h * 3_600_000 if h else 0, hours=h)
    elif q == "/api/guest/reveal":
        data["guest"]["showPass"] = form.get("show") == "1"
    elif q == "/api/guest/remove":
        data["guest"] = {}
    else:
        return None, None
    return True, None


# ---------- web server ----------

STATIC = {
    "/": ("index.html", "text/html; charset=utf-8", False),
    "/index.html": ("index.html", "text/html; charset=utf-8", False),
    "/fonts/Outfit.ttf": ("Outfit.ttf", "font/ttf", True),
    "/icon.svg": ("icon.svg", "image/svg+xml", True),
    "/manifest.webmanifest": ("manifest.webmanifest", "application/manifest+json", True),
}


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "ClearHome"
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def send(self, code, ctype, body, cache=False, cookie=None, extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "max-age=604800" if cache else "no-store")
        if cookie:
            self.send_header("Set-Cookie", cookie)
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def ip(self):
        """The phone's address; for requests the TV passes on, the phone's own address it forwards."""
        ip = self.client_address[0]
        fwd = self.headers.get("X-Forwarded-For", "")
        return fwd.split(",")[0].strip() if fwd and ip == socket.gethostbyname(args.tv) else ip

    def access_email(self):
        """Signed-in email from Cloudflare Access, for requests arriving through the tunnel (local)."""
        if self.client_address[0] not in ("127.0.0.1", "::1"):
            return ""
        return self.headers.get("Cf-Access-Authenticated-User-Email", "").strip().lower()

    def me(self):
        m = re.search(r"(?:^|;\s*)fm=([^;]+)", self.headers.get("Cookie", ""))
        fid = m.group(1) if m else ""
        with lock:
            if fid and find(data["flatmates"], fid):
                return fid
            return data["emails"].get(self.access_email(), "")

    def is_member(self, me):
        if me or self.access_email():
            return True
        with lock:
            if not data["flatmates"]:
                return True  # first-time setup
        if self.headers.get("X-Forwarded-For"):
            return False  # a phone's request the TV passed on: judged by the phone's own link
        try:
            return self.client_address[0] == socket.gethostbyname(args.tv)  # the TV itself
        except OSError:
            return False

    def body(self):
        n = min(65536, int(self.headers.get("Content-Length") or 0))
        return self.rfile.read(n) if n else b""

    def proxy_tv(self, method, raw=b""):
        """Photos live on the TV: pass these requests through."""
        try:
            req = urllib.request.Request(tv_url(self.path), data=raw if method == "POST" else None, method=method,
                                         headers={"Content-Type": self.headers.get("Content-Type", "application/x-www-form-urlencoded")})
            with urllib.request.urlopen(req, timeout=10) as r:
                self.send(r.status, r.headers.get("Content-Type", "application/octet-stream"), r.read(),
                          cache=self.path.startswith("/photo/"))
        except Exception:
            self.send(502, "application/json", b'{"error":"TV not reachable"}')

    def do_GET(self):
        q = urllib.parse.urlsplit(self.path).path
        me, ip = self.me(), self.ip()
        if q in STATIC:
            name, ctype, cache = STATIC[q]
            try:
                with open(os.path.join(WEB, name), "rb") as f:
                    return self.send(200, ctype, f.read(), cache=cache)
            except OSError:
                return self.send(404, "text/plain", b"Not found")
        if q == "/link":
            # Moving from the TV's address: carry over who this phone belongs to, then open the app.
            fid = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).get("fm", [""])[0]
            cookie = None
            with lock:
                if fid and find(data["flatmates"], fid):
                    touch(fid, ip)
                    save()
                    cookie = f"fm={fid}; Max-Age=31536000; Path=/; SameSite=Lax"
            return self.send(302, "text/plain", b"", cookie=cookie, extra={"Location": "/"})
        member = self.is_member(me)
        if q == "/api/state" and not member:
            return self.send(200, "application/json", json.dumps(guest_state()).encode())
        if not member and q != "/link":
            return self.send(403, "application/json", b'{"error":"members only"}')
        if q == "/api/photos" or q.startswith("/photo/"):
            return self.proxy_tv("GET")
        if q in ("/api/state", "/notes"):
            changed = False
            if me:
                with lock:
                    changed = touch(me, ip)
                    save_soon(changed)
            if changed:
                tell_tv()
            st = state(me)
            return self.send(200, "application/json", json.dumps(st["notes"] if q == "/notes" else st, ensure_ascii=False).encode())
        if q == "/api/export":
            with lock:
                return self.send(200, "application/json", json.dumps(data, ensure_ascii=False).encode())
        self.send(404, "text/plain", b"Not found")

    def do_POST(self):
        q = urllib.parse.urlsplit(self.path).path
        raw = self.body()
        me, ip = self.me(), self.ip()
        member = self.is_member(me)
        if not member and q not in GUEST_POSTS:
            return self.send(403, "application/json", b'{"error":"members only"}')
        if q == "/api/photos/place":
            return self.proxy_tv("POST", raw)
        if q == "/api/tv":
            # Today's TV time, sent by the TV every few minutes.
            try:
                tv = json.loads(raw or b"{}")
            except ValueError:
                return self.send(400, "text/plain", b"Bad JSON")
            with lock:
                data["tv"] = {"allowed": bool(tv.get("allowed")), "minutes": int(tv.get("minutes") or 0),
                              "top": tv.get("top", [])[:3], "at": now_ms()}
                save()
            return self.send(200, "application/json", b'{"ok":true}')
        form = {k: v[0] for k, v in urllib.parse.parse_qs(raw.decode("utf-8", "replace"), keep_blank_values=True).items()}
        if not member:
            return self.guest_post(q, form, ip)
        with lock:
            if me:
                touch(me, ip)
            ok, cookie = handle_post(q, form, me, ip)
            if ok is None:
                return self.send(404, "text/plain", b"Not found")
            if cookie and self.access_email():
                data["emails"][self.access_email()] = form.get("id")  # this email is this flatmate
            save()
            st = state(form.get("id") if cookie else me)
        self.send(200 if ok else 400, "application/json", json.dumps(st, ensure_ascii=False).encode(), cookie=cookie)
        tell_tv()


def _guest_post(self, q, form, ip):
    """What a guest may do: leave a note, or link their phone with the flat PIN."""
    if q in ("/api/notes", "/notes"):
        with lock:
            ok = add_note(form.get("text"), (form.get("from") or "Guest").strip()[:24], min(3, int(form.get("hours") or 3)))
            save()
        self.send(200 if ok else 400, "application/json", json.dumps({"ok": ok, **guest_state()}).encode())
        return tell_tv() if ok else None
    if too_many_tries(ip):
        return self.send(429, "application/json", b'{"error":"Too many tries. Wait a few minutes."}')
    with lock:
        if not pin_ok(form.get("pin", "")):
            failures.setdefault(ip, []).append(time.time())
            return self.send(403, "application/json", b'{"error":"Wrong PIN"}')
        if q == "/api/join":  # right PIN: here's who lives here, pick yourself
            people = [{"id": f["id"], "name": f.get("name", ""), "colour": f.get("colour", ""), "emoji": f.get("emoji", ""),
                       "initials": initials(f.get("name"))} for f in data["flatmates"]]
            return self.send(200, "application/json", json.dumps({"ok": True, "flatmates": people}).encode())
        ok, cookie = handle_post("/api/flatmates/claim", form, "", ip)
        save()
        st = state(form.get("id")) if ok else guest_state()
    self.send(200 if ok else 400, "application/json", json.dumps(st, ensure_ascii=False).encode(), cookie=cookie)
    tell_tv()


Handler.guest_post = _guest_post


def main():
    global args
    p = argparse.ArgumentParser(description="Clear Home flat server")
    p.add_argument("--tv", required=True, help="the TV's address, for photos and change notices")
    p.add_argument("--port", type=int, default=PORT)
    p.add_argument("--data", default=os.path.expanduser("~/clearhome/data.json"))
    args = p.parse_args()
    os.makedirs(os.path.dirname(args.data), exist_ok=True)
    load()
    threading.Thread(target=presence_loop, daemon=True).start()
    srv = http.server.ThreadingHTTPServer(("", args.port), Handler)
    srv.daemon_threads = True
    print(f"Clear Home flat server on port {args.port}, TV at {args.tv}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
