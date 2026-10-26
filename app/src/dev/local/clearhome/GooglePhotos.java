package dev.local.clearhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Google Photos via the Ambient API (built for TVs and photo frames).
 *
 * Link: OAuth device flow (code shown on the TV, approved on a phone), with the
 * Ambient "state" parameter so Google then jumps straight to album selection.
 * Sync: lists the curated items for this device and caches them as JPEGs in
 * app storage, so the background keeps working offline.
 *
 * Client ID/secret come from assets/secrets/google.json (never served to the page).
 */
final class GooglePhotos {
    interface Listener { void onChanged(); }

    private static final String SCOPE = "profile https://www.googleapis.com/auth/photosambient.mediaitems";
    private static final String OAUTH = "https://oauth2.googleapis.com";
    private static final String API = "https://photosambient.googleapis.com/v1";
    private static final String DEVICE_NAME = "Clear Home TV";
    private static final long SYNC_EVERY_MS = 2 * 60 * 60 * 1000L; // API allows 240 lists/device/day
    private static final int MAX_CACHED = 150;

    private final Context ctx;
    private final SharedPreferences prefs;
    private final File dir;
    private final Listener listener;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private String clientId = "", clientSecret = "";

    // unlinked | linking | albums | syncing | ready | error
    private volatile String state = "unlinked";
    private volatile String userCode, verifyUrl, error;
    private volatile boolean cancelled;

    GooglePhotos(Context c, Listener l) {
        ctx = c;
        listener = l;
        prefs = c.getSharedPreferences("gphotos", Context.MODE_PRIVATE);
        dir = new File(c.getFilesDir(), "gphotos");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        loadConfig();
        if (prefs.getString("refresh_token", null) != null) {
            state = prefs.getBoolean("sources_set", false) ? "ready" : "albums";
        }
    }

    private void loadConfig() {
        try (InputStream in = ctx.getAssets().open("secrets/google.json")) {
            JSONObject o = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            clientId = o.optString("client_id", "");
            clientSecret = o.optString("client_secret", "");
        } catch (IOException | JSONException ignored) {
        }
    }

    boolean configured() {
        return clientId.endsWith(".apps.googleusercontent.com") && !clientSecret.isEmpty()
                && !clientSecret.startsWith("PASTE_");
    }

    // ---- status for the UI ----

    String status() {
        JSONObject o = new JSONObject();
        try {
            o.put("configured", configured());
            o.put("state", state);
            o.put("userCode", userCode);
            o.put("verifyUrl", verifyUrl);
            o.put("settingsUri", prefs.getString("settings_uri", null));
            o.put("albums", new JSONArray(prefs.getString("albums", "[]")));
            o.put("count", cachedItems().length());
            o.put("lastSync", prefs.getLong("last_sync", 0));
            o.put("error", error);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    /** [{url, date}] for the cached photos, in the order Google returned them. */
    JSONArray cachedItems() {
        JSONArray out = new JSONArray();
        try {
            JSONArray items = new JSONArray(prefs.getString("items", "[]"));
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                String name = it.getString("file");
                if (!new File(dir, name).exists()) continue;
                JSONObject p = new JSONObject();
                p.put("url", "/gphotos/" + name);
                p.put("date", it.optString("createTime", ""));
                out.put(p);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    File file(String name) {
        if (name.contains("/") || name.contains("..")) return null;
        File f = new File(dir, name);
        return f.exists() ? f : null;
    }

    // ---- actions ----

    void link() {
        if (!configured()) {
            fail("Add your Google client ID and secret to secrets/google.json, then rebuild.");
            return;
        }
        cancelled = false;
        error = null;
        userCode = null;
        verifyUrl = null;
        state = "linking";
        changed();
        io.execute(() -> {
            try {
                String requestId = UUID.randomUUID().toString();
                deviceFlow(requestId);
                createDevice(requestId);
                waitForAlbums();
                sync();
            } catch (Exception e) {
                if (!cancelled) fail(e.getMessage());
            }
        });
    }

    void cancel() {
        cancelled = true;
        userCode = null;
        verifyUrl = null;
        error = null;
        state = prefs.getString("refresh_token", null) == null ? "unlinked"
                : prefs.getBoolean("sources_set", false) ? "ready" : "albums";
        changed();
    }

    /** After the albums were changed in the Google Photos app, or on demand. */
    void syncNow() {
        io.execute(() -> {
            try {
                if (!prefs.getBoolean("sources_set", false)) waitForAlbums();
                sync();
            } catch (Exception e) {
                fail(e.getMessage());
            }
        });
    }

    void maybeSync() {
        if (!"ready".equals(state)) return;
        if (System.currentTimeMillis() - prefs.getLong("last_sync", 0) < SYNC_EVERY_MS) return;
        syncNow();
    }

    void disconnect() {
        cancelled = true;
        io.execute(() -> {
            String device = prefs.getString("device_id", null);
            String refresh = prefs.getString("refresh_token", null);
            try {
                if (device != null) http("DELETE", API + "/devices/" + device, null, null, accessToken());
            } catch (Exception ignored) {
            }
            try {
                if (refresh != null) postForm(OAUTH + "/revoke", "token", refresh);
            } catch (Exception ignored) {
            }
            prefs.edit().clear().apply();
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
                f.delete();
            userCode = null;
            verifyUrl = null;
            error = null;
            state = "unlinked";
            changed();
        });
    }

    // ---- steps ----

    private void deviceFlow(String requestId) throws Exception {
        JSONObject st = new JSONObject().put("requestId", requestId).put("displayName", DEVICE_NAME);
        Resp r = postForm(OAUTH + "/device/code", "client_id", clientId, "scope", SCOPE, "state", st.toString());
        if (r.code != 200) throw new IOException("Google refused the sign-in request (" + r.errorText() + ")");
        JSONObject j = r.json();
        String deviceCode = j.getString("device_code");
        userCode = j.getString("user_code");
        verifyUrl = j.optString("verification_url", j.optString("verification_uri", "https://www.google.com/device"));
        int interval = Math.max(5, j.optInt("interval", 5));
        long deadline = System.currentTimeMillis() + j.optInt("expires_in", 1800) * 1000L;
        changed();

        while (!cancelled && System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L);
            if (cancelled) break;
            Resp t = postForm(OAUTH + "/token", "client_id", clientId, "client_secret", clientSecret,
                    "device_code", deviceCode, "grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            if (t.code == 200) {
                saveTokens(t.json());
                userCode = null;
                return;
            }
            String err = t.json().optString("error");
            if ("authorization_pending".equals(err)) continue;
            if ("slow_down".equals(err)) { interval += 5; continue; }
            if ("access_denied".equals(err)) throw new IOException("Sign-in was declined on the phone.");
            throw new IOException("Sign-in failed (" + err + ")");
        }
        throw new IOException(cancelled ? "Cancelled" : "The code expired. Try connecting again.");
    }

    private void createDevice(String requestId) throws Exception {
        JSONObject body = new JSONObject().put("displayName", DEVICE_NAME);
        Resp r = http("POST", API + "/devices?requestId=" + requestId, "application/json",
                body.toString().getBytes(StandardCharsets.UTF_8), accessToken());
        if (r.code != 200) throw new IOException("Couldn't register the TV with Google Photos (" + r.errorText() + ")");
        JSONObject d = r.json();
        prefs.edit()
                .putString("device_id", d.getString("id"))
                .putString("settings_uri", d.optString("settingsUri", null))
                .putBoolean("sources_set", d.optBoolean("mediaSourcesSet", false))
                .apply();
        state = "albums";
        changed();
    }

    private void waitForAlbums() throws Exception {
        String id = prefs.getString("device_id", null);
        if (id == null) throw new IOException("Not connected");
        long deadline = System.currentTimeMillis() + 30 * 60 * 1000L;
        while (!cancelled && System.currentTimeMillis() < deadline) {
            Resp r = http("GET", API + "/devices/" + id, null, null, accessToken());
            if (r.code != 200) throw new IOException("Couldn't check the album selection (" + r.errorText() + ")");
            JSONObject d = r.json();
            saveDevice(d);
            if (d.optBoolean("mediaSourcesSet", false)) return;
            Thread.sleep(pollMs(d));
        }
        if (!cancelled) throw new IOException("No albums were chosen. Open Google Photos settings to pick some.");
    }

    private void sync() throws Exception {
        String id = prefs.getString("device_id", null);
        if (id == null) return;
        if (cachedItems().length() == 0) { state = "syncing"; changed(); }

        // Refresh album names and settings link.
        Resp dr = http("GET", API + "/devices/" + id, null, null, accessToken());
        if (dr.code == 200) saveDevice(dr.json());

        Resp r = http("GET", API + "/mediaItems?deviceId=" + enc(id) + "&pageSize=100", null, null, accessToken());
        if (r.code != 200) throw new IOException("Couldn't list photos (" + r.errorText() + ")");
        JSONArray items = r.json().optJSONArray("mediaItems");
        if (items == null) items = new JSONArray();

        JSONArray fresh = new JSONArray();
        Set<String> keep = new HashSet<>();
        for (int i = 0; i < items.length() && !cancelled; i++) {
            JSONObject it = items.getJSONObject(i);
            JSONObject mf = it.optJSONObject("mediaFile");
            if (mf == null || !mf.optString("mimeType", "image/").startsWith("image/")) continue;
            String name = it.getString("id").replaceAll("[^A-Za-z0-9_-]", "_") + ".jpg";
            File f = new File(dir, name);
            if (!f.exists()) {
                try {
                    download(mf.getString("baseUrl") + "=w1920-h1080", f);
                } catch (IOException e) {
                    continue; // skip this one; try again next sync
                }
            }
            fresh.put(new JSONObject().put("file", name).put("createTime", it.optString("createTime", "")));
            keep.add(name);
            if (fresh.length() == 1 || fresh.length() % 10 == 0) {
                prefs.edit().putString("items", merged(fresh).toString()).apply();
                changed();
            }
        }

        JSONArray all = merged(fresh);
        for (int i = 0; i < all.length(); i++) keep.add(all.getJSONObject(i).getString("file"));
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) if (!keep.contains(f.getName())) //noinspection ResultOfMethodCallIgnored
            f.delete();

        prefs.edit().putString("items", all.toString()).putLong("last_sync", System.currentTimeMillis()).apply();
        error = null;
        state = "ready";
        changed();
    }

    /** Newest list first, then earlier items not in it, capped at MAX_CACHED. */
    private JSONArray merged(JSONArray fresh) throws JSONException {
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < fresh.length(); i++) {
            out.put(fresh.getJSONObject(i));
            seen.add(fresh.getJSONObject(i).getString("file"));
        }
        JSONArray old = new JSONArray(prefs.getString("items", "[]"));
        for (int i = 0; i < old.length() && out.length() < MAX_CACHED; i++) {
            JSONObject o = old.getJSONObject(i);
            if (seen.add(o.getString("file"))) out.put(o);
        }
        return out;
    }

    private void saveDevice(JSONObject d) throws JSONException {
        JSONArray names = new JSONArray();
        JSONArray sources = d.optJSONArray("mediaSources");
        if (sources != null) for (int i = 0; i < sources.length(); i++) {
            String n = sources.getJSONObject(i).optString("displayName", "");
            if (!n.isEmpty()) names.put(n);
        }
        SharedPreferences.Editor e = prefs.edit()
                .putBoolean("sources_set", d.optBoolean("mediaSourcesSet", false))
                .putString("albums", names.toString());
        if (d.has("settingsUri")) e.putString("settings_uri", d.getString("settingsUri"));
        e.apply();
    }

    private static long pollMs(JSONObject device) {
        JSONObject pc = device.optJSONObject("pollingConfig");
        String s = pc == null ? "" : pc.optString("pollInterval", "");
        try {
            if (s.endsWith("s")) return Math.max(3000, (long) (Double.parseDouble(s.substring(0, s.length() - 1)) * 1000));
        } catch (NumberFormatException ignored) {
        }
        return 5000;
    }

    // ---- tokens ----

    private void saveTokens(JSONObject t) {
        SharedPreferences.Editor e = prefs.edit()
                .putString("access_token", t.optString("access_token"))
                .putLong("expires_at", System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000L);
        String refresh = t.optString("refresh_token", "");
        if (!refresh.isEmpty()) e.putString("refresh_token", refresh);
        e.apply();
    }

    private String accessToken() throws Exception {
        if (System.currentTimeMillis() < prefs.getLong("expires_at", 0) - 60_000) {
            return prefs.getString("access_token", "");
        }
        String refresh = prefs.getString("refresh_token", null);
        if (refresh == null) throw new IOException("Not signed in");
        Resp r = postForm(OAUTH + "/token", "client_id", clientId, "client_secret", clientSecret,
                "refresh_token", refresh, "grant_type", "refresh_token");
        if (r.code != 200) {
            if ("invalid_grant".equals(r.json().optString("error"))) {
                prefs.edit().remove("refresh_token").remove("access_token").apply();
                throw new IOException("Google sign-in expired. Connect again.");
            }
            throw new IOException("Couldn't refresh the Google sign-in (" + r.errorText() + ")");
        }
        saveTokens(r.json());
        return prefs.getString("access_token", "");
    }

    // ---- HTTP ----

    private static final class Resp {
        int code;
        String body = "";

        JSONObject json() {
            try {
                return new JSONObject(body);
            } catch (JSONException e) {
                return new JSONObject();
            }
        }

        String errorText() {
            JSONObject j = json();
            Object e = j.opt("error");
            if (e instanceof JSONObject) return ((JSONObject) e).optString("status", "") + " " + ((JSONObject) e).optString("message", "");
            if (e instanceof String) return e + " " + j.optString("error_description", "");
            return "HTTP " + code;
        }
    }

    private static Resp postForm(String url, String... kv) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(kv[i])).append('=').append(enc(kv[i + 1]));
        }
        return http("POST", url, "application/x-www-form-urlencoded", sb.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private static Resp http(String method, String url, String contentType, byte[] body, String bearer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType);
            try (OutputStream o = c.getOutputStream()) {
                o.write(body);
            }
        }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in != null) try (InputStream s = in) {
            r.body = new String(readAll(s), StandardCharsets.UTF_8);
        }
        c.disconnect();
        return r;
    }

    private void download(String url, File dest) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Authorization", "Bearer " + accessToken());
        if (c.getResponseCode() != 200) {
            c.disconnect();
            throw new IOException("HTTP " + c.getResponseCode());
        }
        File tmp = new File(dest.getPath() + ".part");
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
        if (!tmp.renameTo(dest)) throw new IOException("couldn't save photo");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private void fail(String message) {
        error = message;
        userCode = null;
        state = "error";
        changed();
    }

    private void changed() {
        listener.onChanged();
    }
}
