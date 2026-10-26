package dev.local.clearhome;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The flat's shared state and its web app, served on the home Wi-Fi (port 8642):
 * notes, flatmates (with "who's home" presence), rotating chores, and today's TV time.
 *
 * Who's home: each flatmate taps "This phone is mine" in the web app once; the phone's
 * address is remembered (and refreshed whenever it opens the app). Every minute the TV
 * probes those addresses: an answer to a ping, or even a refused TCP connection, means the
 * phone is on the Wi-Fi. Someone counts as home if seen in the last 20 minutes.
 */
final class House {
    interface Listener { void onChanged(); }

    static final int PORT = 8642;
    private static final int MAX_NOTES = 6, MAX_LEN = 240;
    private static final long HOME_WINDOW_MS = 20 * 60_000;
    private static final String[] COLOURS = {"#f2b705", "#4cc3a5", "#e86f6f", "#6aa8ff", "#c58af9", "#ff9f5a"};

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Listener listener;
    private volatile ServerSocket server;
    private volatile String presenceSig = "";
    volatile GoogleDrive photos; // set by Core, for the phone app's Photos tab

    // Flat server on another machine (a Raspberry Pi), e.g. "http://192.168.1.38:8642"; "" = this TV.
    // Set by pushing flat-server.txt into the app's external files folder (see pi/install.sh).
    private volatile JSONObject remoteState;
    private volatile String remoteSig = "";
    private final Object remoteWake = new Object();
    private long lastTvPush;

    House(Context c, Listener l) {
        ctx = c;
        listener = l;
        prefs = c.getSharedPreferences("notes", Context.MODE_PRIVATE); // keeps existing notes
    }

    // ---------- lifecycle ----------

    void start() {
        if (server != null) return;
        Thread t = new Thread(() -> {
            try (ServerSocket s = new ServerSocket(PORT)) {
                server = s;
                android.util.Log.i("ClearHome", "House: web app on " + url());
                while (!s.isClosed()) {
                    Socket client = s.accept();
                    Thread h = new Thread(() -> handle(client), "house-client");
                    h.setDaemon(true);
                    h.start();
                }
            } catch (IOException e) {
                android.util.Log.w("ClearHome", "House server stopped: " + e);
            } finally {
                server = null;
            }
        }, "house-server");
        t.setDaemon(true);
        t.start();

        Thread r = new Thread(() -> {
            while (true) {
                try {
                    syncRemote();
                } catch (Throwable t2) {
                    android.util.Log.w("ClearHome", "Flat server: " + t2);
                }
                synchronized (remoteWake) {
                    try {
                        remoteWake.wait(20_000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "house-remote");
        r.setDaemon(true);
        r.start();

        Thread p = new Thread(() -> {
            while (true) {
                try {
                    if (remote().isEmpty()) probePresence(); // the Pi does this when it's in charge
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t2) {
                    android.util.Log.w("ClearHome", "Presence: " + t2);
                }
            }
        }, "house-presence");
        p.setDaemon(true);
        p.start();
    }

    /** The flat server phones should use: the Pi when one is set, else this TV. */
    static String publicUrl(Context c) {
        String r = c.getSharedPreferences("notes", Context.MODE_PRIVATE).getString("flat_server", "");
        return r.isEmpty() ? url() : r;
    }

    String remote() {
        File f = ctx.getExternalFilesDir(null) == null ? null : new File(ctx.getExternalFilesDir(null), "flat-server.txt");
        if (f != null && f.exists()) {
            try (InputStream in = new java.io.FileInputStream(f)) {
                String v = new String(Http.readAll(in), StandardCharsets.UTF_8).trim().replaceAll("/+$", "");
                prefs.edit().putString("flat_server", v).apply();
                android.util.Log.i("ClearHome", "Flat server: " + (v.isEmpty() ? "this TV" : v));
            } catch (IOException ignored) {
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        return prefs.getString("flat_server", "");
    }

    /** Pulls the flat's state from the Pi, and every few minutes sends it today's TV time. */
    private void syncRemote() throws IOException, JSONException {
        String r = remote();
        if (r.isEmpty()) { remoteState = null; return; }
        Http.Resp resp = Http.request("GET", r + "/api/state", null, null, null);
        if (resp.code == 200 && resp.body != null) {
            JSONObject j = new JSONObject(resp.body);
            j.remove("me");
            j.remove("tv");
            String sig = j.toString();
            remoteState = j;
            if (!sig.equals(remoteSig)) { remoteSig = sig; listener.onChanged(); }
        }
        if (System.currentTimeMillis() - lastTvPush > 5 * 60_000) {
            JSONObject tv;
            synchronized (this) { tv = tvTime(); }
            Http.request("POST", r + "/api/tv", "application/json", bytes(tv.toString()), null);
            lastTvPush = System.currentTimeMillis();
        }
    }

    private void wakeRemote() {
        synchronized (remoteWake) { remoteWake.notifyAll(); }
    }

    /** http://<tv-ip>:8642, or "" without a network address. */
    static String url() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return "http://" + a.getHostAddress() + ":" + PORT;
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    // ---------- state for the TV and the web app ----------

    /** {notes, flatmates:[{id,name,colour,home,linked}], chores:[{id,name,every,assignee,done}], tv:{minutes,top,allowed}} */
    synchronized JSONObject state() {
        JSONObject cached = remoteState;
        if (cached != null && !prefs.getString("flat_server", "").isEmpty()) {
            try {
                return new JSONObject(cached.toString()).put("tv", tvTime());
            } catch (JSONException ignored) {
            }
        }
        JSONObject o = new JSONObject();
        try {
            o.put("notes", notes());
            JSONArray fms = new JSONArray(), raw = flatmatesRaw();
            long now = System.currentTimeMillis();
            for (int i = 0; i < raw.length(); i++) {
                JSONObject f = raw.getJSONObject(i);
                fms.put(new JSONObject().put("id", f.getString("id")).put("name", f.optString("name"))
                        .put("colour", f.optString("colour", COLOURS[i % COLOURS.length]))
                        .put("emoji", f.optString("emoji", "")).put("initials", initials(f.optString("name")))
                        .put("linked", !f.optString("ip").isEmpty())
                        .put("home", now - f.optLong("seen", 0) < HOME_WINDOW_MS));
            }
            o.put("flatmates", fms);
            o.put("chores", choresToday(raw));
            o.put("shopping", jsonArray("shopping"));
            o.put("reminders", remindersUpcoming());
            o.put("guest", guest());
            o.put("tv", tvTime());
        } catch (JSONException ignored) {
        }
        return o;
    }

    synchronized JSONArray notes() {
        JSONArray out = new JSONArray();
        long now = System.currentTimeMillis();
        try {
            JSONArray all = new JSONArray(prefs.getString("notes", "[]"));
            for (int i = 0; i < all.length(); i++) if (all.getJSONObject(i).optLong("until") > now) out.put(all.getJSONObject(i));
        } catch (JSONException ignored) {
        }
        return out;
    }

    /** Two letters ("Aj", "Ab", "An"), since one initial isn't enough when names share it. */
    static String initials(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return "?";
        String[] parts = n.split("\\s+");
        if (parts.length > 1) return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase(Locale.ROOT);
        return n.length() == 1 ? n.toUpperCase(Locale.ROOT) : n.substring(0, 1).toUpperCase(Locale.ROOT) + n.substring(1, 2).toLowerCase(Locale.ROOT);
    }

    private JSONArray flatmatesRaw() {
        try {
            return new JSONArray(prefs.getString("flatmates", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private JSONArray jsonArray(String key) {
        try {
            return new JSONArray(prefs.getString(key, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private void putArray(String key, JSONArray a) { prefs.edit().putString(key, a.toString()).apply(); }

    // ---------- guest Wi-Fi: name + password, shown on the TV with a join QR for a few hours ----------

    /** {ssid, pass, security: WPA|WEP|nopass, until (ms; 0 = hidden), on} */
    synchronized JSONObject guest() {
        JSONObject g;
        try {
            g = new JSONObject(prefs.getString("guest", "{}"));
        } catch (JSONException e) {
            g = new JSONObject();
        }
        try {
            long until = g.optLong("until", 0);
            g.put("on", until > System.currentTimeMillis() && !g.optString("ssid").isEmpty());
        } catch (JSONException ignored) {
        }
        return g;
    }

    private synchronized boolean saveGuest(JSONObject form) {
        String ssid = form.optString("ssid").trim(), pass = form.optString("pass"), sec = form.optString("security", "WPA");
        if (ssid.isEmpty() || ssid.length() > 32 || pass.length() > 63) return false;
        if (!sec.equals("WEP") && !sec.equals("nopass")) sec = "WPA";
        try {
            JSONObject g = guest();
            g.remove("on");
            g.put("ssid", ssid).put("pass", sec.equals("nopass") ? "" : pass).put("security", sec);
            prefs.edit().putString("guest", g.toString()).apply();
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    /** Whether the TV prints the password, or only dots (the QR works either way). */
    private synchronized void revealGuest(boolean show) {
        try {
            JSONObject g = guest();
            g.remove("on");
            prefs.edit().putString("guest", g.put("showPass", show).toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    /** Show the card for this many hours (0 hides it). */
    private synchronized void showGuest(int hours) {
        try {
            JSONObject g = guest();
            g.remove("on");
            g.put("until", hours <= 0 ? 0 : System.currentTimeMillis() + Math.min(hours, 48) * 3_600_000L).put("hours", Math.max(0, Math.min(hours, 48)));
            prefs.edit().putString("guest", g.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    // ---------- reminders (rent, bills, birthdays): a date, optionally repeating monthly ----------

    /** Reminders with their next date and days left, soonest first. */
    synchronized JSONArray remindersUpcoming() {
        JSONArray raw = jsonArray("reminders");
        java.util.List<JSONObject> list = new java.util.ArrayList<>();
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < raw.length(); i++) {
            JSONObject r = raw.optJSONObject(i);
            if (r == null) continue;
            try {
                String[] d = r.optString("date").split("-");
                Calendar next = (Calendar) today.clone();
                next.set(Integer.parseInt(d[0]), Integer.parseInt(d[1]) - 1, 1);
                int day = Integer.parseInt(d[2]);
                boolean monthly = "month".equals(r.optString("repeat"));
                if (monthly) {
                    next = (Calendar) today.clone();
                    next.set(Calendar.DAY_OF_MONTH, 1);
                    next.set(Calendar.DAY_OF_MONTH, Math.min(day, next.getActualMaximum(Calendar.DAY_OF_MONTH)));
                    if (next.before(today)) {
                        next.add(Calendar.MONTH, 1);
                        next.set(Calendar.DAY_OF_MONTH, Math.min(day, next.getActualMaximum(Calendar.DAY_OF_MONTH)));
                    }
                } else {
                    next.set(Calendar.DAY_OF_MONTH, Math.min(day, next.getActualMaximum(Calendar.DAY_OF_MONTH)));
                    if (next.before(today)) continue; // past one-off reminders drop off
                }
                long days = Math.round((next.getTimeInMillis() - today.getTimeInMillis()) / 86_400_000.0);
                list.add(new JSONObject(r.toString())
                        .put("next", String.format(Locale.US, "%04d-%02d-%02d", next.get(Calendar.YEAR), next.get(Calendar.MONTH) + 1, next.get(Calendar.DAY_OF_MONTH)))
                        .put("daysLeft", days));
            } catch (Exception ignored) {
            }
        }
        list.sort((a, b) -> Long.compare(a.optLong("daysLeft"), b.optLong("daysLeft")));
        JSONArray out = new JSONArray();
        for (JSONObject o : list) out.put(o);
        return out;
    }

    private JSONArray choresRaw() {
        try {
            return new JSONArray(prefs.getString("chores", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    // ---------- chores ----------

    private static long period(String every) {
        long day = Math.floorDiv(System.currentTimeMillis() + Calendar.getInstance().getTimeZone().getOffset(System.currentTimeMillis()), 86_400_000L);
        return "week".equals(every) ? Math.floorDiv(day + 3, 7) : day; // weeks start on Monday
    }

    private static int indexOf(JSONArray fms, String id) {
        for (int i = 0; i < fms.length(); i++) if (fms.optJSONObject(i).optString("id").equals(id)) return i;
        return -1;
    }

    /**
     * Today's chores with whose turn it is. A chore either rotates through the flatmates in
     * order or always belongs to one ("owner"); anyone can take over the current turn
     * ("I'll do it"), which leaves the rotation itself unchanged.
     */
    private JSONArray choresToday(JSONArray fms) throws JSONException {
        JSONArray out = new JSONArray(), cs = choresRaw();
        JSONObject done = new JSONObject(prefs.getString("choresDone", "{}"));
        JSONObject takes = new JSONObject(prefs.getString("choreTakes", "{}"));
        for (int i = 0; i < cs.length(); i++) {
            JSONObject c = cs.getJSONObject(i);
            String every = c.optString("every", "day");
            long p = period(every);
            String key = c.getString("id") + ":" + p;
            int owner = indexOf(fms, c.optString("owner")), idx = -1, nextIdx = -1;
            if (owner >= 0) idx = owner;
            else if (fms.length() > 0) {
                idx = (int) Math.floorMod(p + c.optInt("offset", i), (long) fms.length());
                nextIdx = (idx + 1) % fms.length();
            }
            int taker = indexOf(fms, takes.optString(key));
            String swappedFrom = "";
            if (taker >= 0 && taker != idx) {
                if (idx >= 0) swappedFrom = fms.getJSONObject(idx).optString("name");
                idx = taker;
            }
            JSONObject o = new JSONObject().put("id", c.getString("id")).put("name", c.optString("name")).put("every", every)
                    .put("owner", owner >= 0 ? fms.getJSONObject(owner).optString("id") : "")
                    .put("assignee", idx >= 0 ? fms.getJSONObject(idx).optString("name") : "")
                    .put("assigneeId", idx >= 0 ? fms.getJSONObject(idx).optString("id") : "")
                    .put("swappedFrom", swappedFrom)
                    .put("next", nextIdx >= 0 ? fms.getJSONObject(nextIdx).optString("name") : "")
                    .put("done", done.optBoolean(key));
            out.put(o);
        }
        return out;
    }

    // ---------- TV time (needs Usage access) ----------

    private boolean usageAllowed() {
        AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /** {allowed, minutes, top:[{name, minutes}]}: foreground time today, excluding Clear Home. */
    private JSONObject tvTime() throws JSONException {
        JSONObject o = new JSONObject().put("allowed", usageAllowed());
        if (!o.getBoolean("allowed")) return o;
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long start = c.getTimeInMillis(), now = System.currentTimeMillis();
        UsageStatsManager um = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        UsageEvents ev = um.queryEvents(start, now);
        Map<String, Long> fg = new HashMap<>(), since = new HashMap<>();
        UsageEvents.Event e = new UsageEvents.Event();
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            String pkg = e.getPackageName();
            int t = e.getEventType();
            if (t == UsageEvents.Event.ACTIVITY_RESUMED) since.put(pkg, e.getTimeStamp());
            else if (t == UsageEvents.Event.ACTIVITY_PAUSED && since.containsKey(pkg)) {
                fg.put(pkg, fg.getOrDefault(pkg, 0L) + e.getTimeStamp() - since.remove(pkg));
            }
        }
        for (Map.Entry<String, Long> s : since.entrySet()) fg.put(s.getKey(), fg.getOrDefault(s.getKey(), 0L) + now - s.getValue());
        fg.remove(ctx.getPackageName());
        long total = 0;
        java.util.List<Map.Entry<String, Long>> list = new java.util.ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        for (Map.Entry<String, Long> x : fg.entrySet()) {
            // Only count real apps (ones with a launcher entry), not system services.
            if (pm.getLeanbackLaunchIntentForPackage(x.getKey()) == null && pm.getLaunchIntentForPackage(x.getKey()) == null) continue;
            total += x.getValue();
            list.add(x);
        }
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        JSONArray top = new JSONArray();
        for (int i = 0; i < Math.min(3, list.size()); i++) {
            String label = list.get(i).getKey();
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(label, 0)).toString();
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            top.put(new JSONObject().put("name", label).put("minutes", list.get(i).getValue() / 60_000));
        }
        return o.put("minutes", total / 60_000).put("top", top);
    }

    // ---------- presence ----------

    private void probePresence() throws JSONException {
        JSONArray fms;
        synchronized (this) { fms = flatmatesRaw(); }
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (int i = 0; i < fms.length(); i++) {
            JSONObject f = fms.getJSONObject(i);
            String ip = f.optString("ip");
            if (ip.isEmpty()) continue;
            if (alive(ip)) { f.put("seen", now); changed = true; }
        }
        if (changed) synchronized (this) {
            // Merge "seen" back without clobbering edits made meanwhile.
            JSONArray cur = flatmatesRaw();
            for (int i = 0; i < cur.length(); i++) {
                for (int j = 0; j < fms.length(); j++) {
                    if (cur.getJSONObject(i).optString("id").equals(fms.getJSONObject(j).optString("id"))) {
                        cur.getJSONObject(i).put("seen", Math.max(cur.getJSONObject(i).optLong("seen"), fms.getJSONObject(j).optLong("seen")));
                    }
                }
            }
            prefs.edit().putString("flatmates", cur.toString()).apply();
        }
        String sig = state().optJSONArray("flatmates").toString();
        if (!sig.equals(presenceSig)) { presenceSig = sig; listener.onChanged(); }
    }

    /** A phone is on the Wi-Fi if it answers a ping, or refuses (rather than ignores) a TCP connection. */
    private static boolean alive(String ip) {
        try {
            if (InetAddress.getByName(ip).isReachable(1500)) return true;
        } catch (IOException ignored) {
        }
        for (int port : new int[]{62078, 7000, 9}) { // 62078: iPhone sync service; others usually refused
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(ip, port), 800);
                return true;
            } catch (java.net.ConnectException e) {
                String m = String.valueOf(e.getMessage());
                if (m.contains("refused") || m.contains("ECONNREFUSED")) return true;
            } catch (IOException ignored) {
            }
        }
        return false;
    }

    // ---------- web server ----------

    private void handle(Socket client) {
        try (Socket c = client) {
            c.setSoTimeout(10000);
            String ip = c.getInetAddress().getHostAddress();
            InputStream in = new BufferedInputStream(c.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0], path = parts[1];
            int length = 0;
            String cookie = "";
            for (String h; (h = readLine(in)) != null && !h.isEmpty(); ) {
                String lh = h.toLowerCase(Locale.ROOT);
                if (lh.startsWith("content-length:")) length = Math.min(8192, Integer.parseInt(h.substring(15).trim()));
                if (lh.startsWith("cookie:")) cookie = h.substring(7).trim();
            }
            byte[] body = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(body, read, length - read);
                if (n < 0) break;
                read += n;
            }
            JSONObject form = parseForm(new String(body, 0, read, StandardCharsets.UTF_8));
            String me = cookieValue(cookie, "fm");
            OutputStream out = c.getOutputStream();
            String q = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
            String remote = prefs.getString("flat_server", "");

            if (q.equals("/api/changed")) { // the Pi saying something changed
                wakeRemote();
                send(out, 200, "application/json", bytes("{\"ok\":true}"), null);
                return;
            }
            if (!remote.isEmpty() && method.equals("GET") && (q.equals("/") || q.equals("/index.html"))) {
                // Phones that still open the TV's address move to the Pi, staying linked to their name.
                String to = remote + "/link" + (me.isEmpty() ? "" : "?fm=" + URLEncoder.encode(me, "UTF-8"));
                out.write(bytes("HTTP/1.1 302 Found\r\nLocation: " + to + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"));
                out.flush();
                return;
            }
            boolean photosPath = q.startsWith("/photo/") || q.startsWith("/api/photos");
            if (!remote.isEmpty() && photosPath && !ip.equals(hostOf(remote))) {
                // With the Pi in charge, photos go through it (it checks who's asking); not straight from the TV.
                send(out, 404, "text/plain", bytes("Not found"), null);
                return;
            }
            if (!remote.isEmpty() && !photosPath && (q.startsWith("/api/") || q.equals("/notes"))) {
                forward(out, method, remote + path, body, read, cookie, ip);
                return;
            }
            // A linked phone opening the app refreshes its address; if they were away, tell the TV now.
            if (!me.isEmpty() && touch(me, ip)) listener.onChanged();

            if (method.equals("GET") && (q.equals("/") || q.equals("/index.html"))) {
                send(out, 200, "text/html; charset=utf-8", asset("web/index.html"), null);
                return;
            }
            if (method.equals("GET") && q.equals("/fonts/Outfit.ttf")) {
                sendCached(out, "font/ttf", asset("fonts/Outfit.ttf"));
                return;
            }
            if (method.equals("GET") && q.equals("/icon.svg")) {
                sendCached(out, "image/svg+xml", asset("web/icon.svg"));
                return;
            }
            if (method.equals("GET") && q.startsWith("/photo/") && photos != null) {
                java.io.File f = photos.file(q.substring(7));
                if (f == null) { send(out, 404, "text/plain", bytes("Not found"), null); return; }
                try (InputStream img = new java.io.FileInputStream(f)) { sendCached(out, "image/jpeg", Http.readAll(img)); }
                return;
            }
            if (method.equals("GET") && q.equals("/api/photos")) {
                JSONArray items = photos == null ? new JSONArray() : photos.cachedItems();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    it.put("thumb", it.optString("url").replace("/gphotos/", "/photo/")).remove("url");
                }
                send(out, 200, "application/json", bytes(new JSONObject().put("items", items).toString()), null);
                return;
            }
            if (method.equals("POST") && q.equals("/api/photos/place") && photos != null) {
                photos.setPlace(form.optString("id"), form.optString("place"), form.optString("source", "manual"));
                send(out, 200, "application/json", bytes("{\"ok\":true}"), null);
                return;
            }
            if (method.equals("GET") && q.equals("/manifest.webmanifest")) {
                sendCached(out, "application/manifest+json", asset("web/manifest.webmanifest"));
                return;
            }
            String setCookie = null;
            boolean ok = true;
            switch (method + " " + q) {
                case "GET /api/state": break;
                case "GET /notes": send(out, 200, "application/json", bytes(notes().toString()), null); return; // old page
                case "POST /notes":
                case "POST /api/notes": {
                    String from = form.optString("from");
                    if (from.isEmpty() && !me.isEmpty()) from = nameOf(me); // sign notes from linked phones
                    ok = addNote(form.optString("text"), from, form.optInt("hours", 3));
                    break;
                }
                case "POST /notes/delete":
                case "POST /api/notes/delete": removeNote(form.optString("id")); break;
                case "POST /api/flatmates/add": ok = addFlatmate(form.optString("name")); break;
                case "POST /api/flatmates/delete": removeFlatmate(form.optString("id")); break;
                case "POST /api/flatmates/update": ok = updateFlatmate(form.optString("id"), form); break;
                case "POST /api/flatmates/unlink": ok = unlink(form.optString("id")); break;
                case "POST /api/flatmates/claim":
                    if (claim(form.optString("id"), ip)) setCookie = "fm=" + form.optString("id") + "; Max-Age=31536000; Path=/; SameSite=Lax";
                    else ok = false;
                    break;
                case "POST /api/chores/add": ok = addChore(form.optString("name"), form.optString("every", "day"), form.optString("owner")); break;
                case "POST /api/chores/update": ok = updateChore(form.optString("id"), form); break;
                case "POST /api/chores/take": ok = !me.isEmpty() && takeChore(form.optString("id"), me); break;
                case "POST /api/chores/delete": removeChore(form.optString("id")); break;
                case "POST /api/chores/done": toggleDone(form.optString("id")); break;
                case "POST /api/shop/add": ok = addItem("shopping", form.optString("text"), me, 60); break;
                case "POST /api/shop/toggle": toggleItem("shopping", form.optString("id")); break;
                case "POST /api/shop/delete": removeItem("shopping", form.optString("id")); break;
                case "POST /api/shop/clear": clearDone("shopping"); break;
                case "POST /api/remind/add": ok = addReminder(form.optString("title"), form.optString("date"), form.optString("repeat"), me); break;
                case "POST /api/guest/save": ok = saveGuest(form); break;
                case "POST /api/guest/reveal": revealGuest("1".equals(form.optString("show"))); break;
                case "POST /api/guest/remove": prefs.edit().remove("guest").apply(); break;
                case "POST /api/guest/show": showGuest(form.optInt("hours", 0)); break;
                case "POST /api/remind/delete": removeItem("reminders", form.optString("id")); break;
                default:
                    send(out, 404, "text/plain", bytes("Not found"), null);
                    return;
            }
            JSONObject st = state();
            try {
                st.put("me", setCookie != null ? form.optString("id") : me);
            } catch (JSONException ignored) {
            }
            send(out, ok ? 200 : 400, "application/json", bytes(st.toString()), setCookie);
            if (method.equals("POST")) listener.onChanged();
        } catch (Exception ignored) {
        }
    }

    private static String hostOf(String url) {
        try {
            return InetAddress.getByName(new java.net.URL(url).getHost()).getHostAddress();
        } catch (IOException e) {
            return "";
        }
    }

    /** Passes a phone's request on to the Pi (an old page still pointed at the TV), with its cookie and address. */
    private static void forward(OutputStream out, String method, String url, byte[] body, int len, String cookie, String ip) throws IOException {
        java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        h.setRequestMethod(method);
        h.setConnectTimeout(5000);
        h.setReadTimeout(15000);
        h.setInstanceFollowRedirects(false);
        if (!cookie.isEmpty()) h.setRequestProperty("Cookie", cookie);
        h.setRequestProperty("X-Forwarded-For", ip);
        if (method.equals("POST")) {
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream o = h.getOutputStream()) { o.write(body, 0, len); }
        }
        int code;
        byte[] resp;
        try {
            code = h.getResponseCode();
            InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
            resp = in == null ? new byte[0] : Http.readAll(in);
        } catch (IOException e) {
            send(out, 404, "application/json", bytes("{\"error\":\"flat server not reachable\"}"), null);
            return;
        }
        send(out, code == 200 ? 200 : code == 404 ? 404 : 400, h.getContentType() == null ? "application/json" : h.getContentType(),
                resp, h.getHeaderField("Set-Cookie"));
        h.disconnect();
    }

    private byte[] asset(String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            return Http.readAll(in);
        }
    }

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static String cookieValue(String cookie, String key) {
        for (String kv : cookie.split(";")) {
            String[] p = kv.trim().split("=", 2);
            if (p.length == 2 && p[0].equals(key)) return p[1];
        }
        return "";
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') break;
            if (ch != '\r') b.write(ch);
            if (b.size() > 8192) break;
        }
        return ch == -1 && b.size() == 0 ? null : b.toString("UTF-8");
    }

    private static void send(OutputStream out, int code, String type, byte[] body, String setCookie) throws IOException {
        String status = code == 200 ? "OK" : code == 400 ? "Bad Request" : "Not Found";
        String head = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\n" + (setCookie != null ? "Set-Cookie: " + setCookie + "\r\n" : "")
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static void sendCached(OutputStream out, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: max-age=604800\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static JSONObject parseForm(String s) {
        JSONObject o = new JSONObject();
        for (String kv : s.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            try {
                o.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return o;
    }

    // ---------- mutations ----------

    private synchronized boolean addNote(String text, String from, int hours) {
        text = text == null ? "" : text.trim();
        if (text.isEmpty()) return false;
        if (text.length() > MAX_LEN) text = text.substring(0, MAX_LEN);
        hours = Math.max(1, Math.min(48, hours));
        JSONArray cur = notes(), next = new JSONArray();
        try {
            long now = System.currentTimeMillis();
            next.put(new JSONObject().put("id", UUID.randomUUID().toString()).put("text", text)
                    .put("from", from == null ? "" : from.trim()).put("at", now).put("until", now + hours * 3_600_000L));
            for (int i = 0; i < cur.length() && next.length() < MAX_NOTES; i++) next.put(cur.getJSONObject(i));
        } catch (JSONException e) {
            return false;
        }
        prefs.edit().putString("notes", next.toString()).apply();
        return true;
    }

    private synchronized void removeNote(String id) {
        JSONArray cur = notes(), next = new JSONArray();
        try {
            for (int i = 0; i < cur.length(); i++) if (!cur.getJSONObject(i).optString("id").equals(id)) next.put(cur.getJSONObject(i));
        } catch (JSONException ignored) {
        }
        prefs.edit().putString("notes", next.toString()).apply();
    }

    private synchronized boolean addFlatmate(String name) {
        name = name == null ? "" : name.trim();
        if (name.isEmpty() || name.length() > 24) return false;
        JSONArray fms = flatmatesRaw();
        if (fms.length() >= 8) return false;
        try {
            fms.put(new JSONObject().put("id", UUID.randomUUID().toString().substring(0, 8)).put("name", name)
                    .put("colour", COLOURS[fms.length() % COLOURS.length]));
        } catch (JSONException e) {
            return false;
        }
        prefs.edit().putString("flatmates", fms.toString()).apply();
        return true;
    }

    private synchronized void removeFlatmate(String id) {
        JSONArray cur = flatmatesRaw(), next = new JSONArray();
        try {
            for (int i = 0; i < cur.length(); i++) if (!cur.getJSONObject(i).optString("id").equals(id)) next.put(cur.getJSONObject(i));
        } catch (JSONException ignored) {
        }
        prefs.edit().putString("flatmates", next.toString()).apply();
    }

    private synchronized String nameOf(String id) {
        JSONArray cur = flatmatesRaw();
        for (int i = 0; i < cur.length(); i++) {
            JSONObject f = cur.optJSONObject(i);
            if (f != null && f.optString("id").equals(id)) return f.optString("name");
        }
        return "";
    }

    private synchronized boolean updateFlatmate(String id, JSONObject form) {
        JSONArray cur = flatmatesRaw();
        boolean found = false;
        try {
            for (int i = 0; i < cur.length(); i++) {
                JSONObject f = cur.getJSONObject(i);
                if (!f.optString("id").equals(id)) continue;
                String name = form.optString("name", "").trim();
                if (!name.isEmpty() && name.length() <= 24) f.put("name", name);
                if (form.has("emoji")) {
                    String e = form.optString("emoji").trim();
                    f.put("emoji", e.length() <= 8 ? e : e.substring(0, 8)); // one emoji (may be several code units)
                }
                String colour = form.optString("colour", "");
                if (colour.matches("#[0-9a-fA-F]{6}")) f.put("colour", colour);
                found = true;
            }
        } catch (JSONException e) {
            return false;
        }
        if (found) prefs.edit().putString("flatmates", cur.toString()).apply();
        return found;
    }

    /** "This phone is mine": link a flatmate to the requesting phone's address. */
    private synchronized boolean claim(String id, String ip) {
        JSONArray cur = flatmatesRaw();
        boolean found = false;
        try {
            for (int i = 0; i < cur.length(); i++) {
                JSONObject f = cur.getJSONObject(i);
                if (f.optString("id").equals(id)) { f.put("ip", ip).put("seen", System.currentTimeMillis()); found = true; }
                else if (ip.equals(f.optString("ip"))) f.remove("ip"); // a phone belongs to one person
            }
        } catch (JSONException e) {
            return false;
        }
        if (found) prefs.edit().putString("flatmates", cur.toString()).apply();
        return found;
    }

    /** Forget a flatmate's phone (new phone, or linked by mistake); they show as away until it's linked again. */
    private synchronized boolean unlink(String id) {
        JSONArray cur = flatmatesRaw();
        try {
            for (int i = 0; i < cur.length(); i++) {
                JSONObject f = cur.getJSONObject(i);
                if (!f.optString("id").equals(id)) continue;
                f.remove("ip");
                f.remove("seen");
                prefs.edit().putString("flatmates", cur.toString()).apply();
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Returns true if this flatmate was away until now. */
    private synchronized boolean touch(String id, String ip) {
        JSONArray cur = flatmatesRaw();
        long now = System.currentTimeMillis();
        boolean wasAway = false;
        try {
            for (int i = 0; i < cur.length(); i++) {
                JSONObject f = cur.getJSONObject(i);
                if (!f.optString("id").equals(id)) continue;
                wasAway = now - f.optLong("seen", 0) >= HOME_WINDOW_MS;
                f.put("ip", ip).put("seen", now);
                break;
            }
        } catch (JSONException ignored) {
        }
        prefs.edit().putString("flatmates", cur.toString()).apply();
        return wasAway;
    }

    private synchronized boolean addItem(String key, String text, String by, int maxLen) {
        text = text == null ? "" : text.trim();
        if (text.isEmpty()) return false;
        if (text.length() > maxLen) text = text.substring(0, maxLen);
        JSONArray cur = jsonArray(key);
        if (cur.length() >= 100) return false;
        try {
            cur.put(new JSONObject().put("id", UUID.randomUUID().toString().substring(0, 8)).put("text", text)
                    .put("by", nameOf(by)).put("done", false).put("at", System.currentTimeMillis()));
        } catch (JSONException e) {
            return false;
        }
        putArray(key, cur);
        return true;
    }

    private synchronized void toggleItem(String key, String id) {
        JSONArray cur = jsonArray(key);
        try {
            for (int i = 0; i < cur.length(); i++) {
                JSONObject o = cur.getJSONObject(i);
                if (o.optString("id").equals(id)) o.put("done", !o.optBoolean("done"));
            }
        } catch (JSONException ignored) {
        }
        putArray(key, cur);
    }

    private synchronized void removeItem(String key, String id) {
        JSONArray cur = jsonArray(key), next = new JSONArray();
        for (int i = 0; i < cur.length(); i++) {
            JSONObject o = cur.optJSONObject(i);
            if (o != null && !o.optString("id").equals(id)) next.put(o);
        }
        putArray(key, next);
    }

    private synchronized void clearDone(String key) {
        JSONArray cur = jsonArray(key), next = new JSONArray();
        for (int i = 0; i < cur.length(); i++) {
            JSONObject o = cur.optJSONObject(i);
            if (o != null && !o.optBoolean("done")) next.put(o);
        }
        putArray(key, next);
    }

    private synchronized boolean addReminder(String title, String date, String repeat, String by) {
        title = title == null ? "" : title.trim();
        if (title.isEmpty() || title.length() > 50 || date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        JSONArray cur = jsonArray("reminders");
        if (cur.length() >= 50) return false;
        try {
            cur.put(new JSONObject().put("id", UUID.randomUUID().toString().substring(0, 8)).put("title", title)
                    .put("date", date).put("repeat", "month".equals(repeat) ? "month" : "none").put("by", nameOf(by)));
        } catch (JSONException e) {
            return false;
        }
        putArray("reminders", cur);
        return true;
    }

    private synchronized boolean addChore(String name, String every, String owner) {
        name = name == null ? "" : name.trim();
        if (name.isEmpty() || name.length() > 40) return false;
        JSONArray cs = choresRaw();
        try {
            cs.put(new JSONObject().put("id", UUID.randomUUID().toString().substring(0, 8)).put("name", name)
                    .put("every", "week".equals(every) ? "week" : "day").put("offset", cs.length())
                    .put("owner", owner == null ? "" : owner));
        } catch (JSONException e) {
            return false;
        }
        prefs.edit().putString("chores", cs.toString()).apply();
        return true;
    }

    /** Rename, change how often, or change who does it ("owner": a flatmate id, or "" to rotate). */
    private synchronized boolean updateChore(String id, JSONObject form) {
        JSONArray cs = choresRaw();
        try {
            for (int i = 0; i < cs.length(); i++) {
                JSONObject c = cs.getJSONObject(i);
                if (!c.optString("id").equals(id)) continue;
                String name = form.optString("name", "").trim();
                if (!name.isEmpty() && name.length() <= 40) c.put("name", name);
                if (form.has("every")) c.put("every", "week".equals(form.optString("every")) ? "week" : "day");
                if (form.has("owner")) c.put("owner", form.optString("owner"));
                prefs.edit().putString("chores", cs.toString()).apply();
                return true;
            }
        } catch (JSONException ignored) {
        }
        return false;
    }

    /** "I'll do it": take over this turn of a chore. Taking it again hands it back. */
    private synchronized boolean takeChore(String id, String who) {
        try {
            String every = null;
            JSONArray cs = choresRaw();
            for (int i = 0; i < cs.length(); i++) if (cs.getJSONObject(i).optString("id").equals(id)) every = cs.getJSONObject(i).optString("every", "day");
            if (every == null) return false;
            JSONObject takes = new JSONObject(prefs.getString("choreTakes", "{}"));
            String key = id + ":" + period(every);
            if (who.equals(takes.optString(key))) takes.remove(key); else takes.put(key, who);
            prefs.edit().putString("choreTakes", pruned(takes).toString()).apply();
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    /** Drop "chore:period" entries older than ~2 months. */
    private static JSONObject pruned(JSONObject byPeriod) throws JSONException {
        long cutoff = period("day") - 60;
        JSONObject kept = new JSONObject();
        java.util.Iterator<String> it = byPeriod.keys();
        while (it.hasNext()) {
            String k = it.next();
            try {
                long p = Long.parseLong(k.substring(k.indexOf(':') + 1));
                if (p >= cutoff || p > 10_000 && p < 100_000 && p * 7 >= cutoff) kept.put(k, byPeriod.get(k));
            } catch (NumberFormatException ignored) {
            }
        }
        return kept;
    }

    private synchronized void removeChore(String id) {
        JSONArray cur = choresRaw(), next = new JSONArray();
        try {
            for (int i = 0; i < cur.length(); i++) if (!cur.getJSONObject(i).optString("id").equals(id)) next.put(cur.getJSONObject(i));
        } catch (JSONException ignored) {
        }
        prefs.edit().putString("chores", next.toString()).apply();
    }

    private synchronized void toggleDone(String id) {
        try {
            JSONArray cs = choresRaw();
            String every = "day";
            for (int i = 0; i < cs.length(); i++) if (cs.getJSONObject(i).optString("id").equals(id)) every = cs.getJSONObject(i).optString("every", "day");
            JSONObject done = new JSONObject(prefs.getString("choresDone", "{}"));
            String key = id + ":" + period(every);
            if (done.optBoolean(key)) done.remove(key); else done.put(key, true);
            prefs.edit().putString("choresDone", pruned(done).toString()).apply();
        } catch (JSONException ignored) {
        }
    }
}
