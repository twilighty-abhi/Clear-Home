package dev.local.clearhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Background photos from a Google Drive folder, with place names.
 *
 * Google only allows Drive's app-created-files scope in the TV sign-in flow, so
 * the one-time sign-in (drive.readonly) happens on a computer via
 * google-signin.sh, which also picks the folder and drops google-signin.json
 * into this app's external files dir over adb. It's imported into private
 * storage and deleted.
 *
 * Sync: lists the folder's images, downloads new ones, scales them to 1080p
 * (honouring EXIF rotation), drops deleted ones, and turns each photo's GPS
 * position into a place name via OpenStreetMap Nominatim (cached, ≤1 req/s).
 */
final class GoogleDrive {
    interface Listener { void onChanged(); }

    private static final String DRIVE = "https://www.googleapis.com/drive/v3";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String HANDOFF = "google-signin.json";
    private static final long SYNC_EVERY_MS = 2 * 60 * 60 * 1000L;
    private static final int MAX_PHOTOS = 500;
    private static final String USER_AGENT = "ClearHome/1.0 (personal Android TV home screen)";
    // Bump to re-check photos cached by an older version
    // (v2: read EXIF from the file itself; v3: quality check + Takeout sidecars).
    private static final int META_VERSION = 4; // v4: keep the file's own EXIF date separately
    // Bump to force one folder check after an update that needs no re-download.
    private static final int SYNC_REV = 8;

    // Quality check thresholds (applied once per photo, after EXIF rotation).
    private static final float MIN_ASPECT = 1.25f;   // landscape only: 4:3 (1.33) up to 16:9 (1.78) and wider
    private static final int MIN_SHORT_SIDE = 1000;  // roughly 1080p or better
    private static final double MIN_SHARPNESS = 15;  // Laplacian variance on a 320px-wide copy; lower = blurry
    private static final int MIN_BRIGHTNESS = 28, MAX_BRIGHTNESS = 230; // mean luminance, 0-255

    /** A photo that downloaded fine but failed the quality check. */
    private static final class Rejected extends IOException {
        final String reason;
        Rejected(String reason) { super(reason); this.reason = reason; }
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final File dir;
    private final Listener listener;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    // signin | syncing | ready | empty | error
    private volatile String state;
    private volatile String error;
    private volatile int done, total;
    private volatile boolean busy, stop;
    private long lastGeocode;

    GoogleDrive(Context c, Listener l) {
        ctx = c;
        listener = l;
        prefs = c.getSharedPreferences("gdrive", Context.MODE_PRIVATE);
        dir = new File(c.getFilesDir(), "gdrive");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        c.getExternalFilesDir(null); // create the hand-off folder with the right ownership
        boolean imported = importSignin();
        if (prefs.getInt("meta_version", 1) < META_VERSION) {
            // New metadata handling: re-check the folder now instead of waiting for the schedule.
            prefs.edit().putInt("meta_version", META_VERSION).putLong("last_sync", 0).apply();
        }
        if (prefs.getInt("sync_rev", 1) < SYNC_REV) {
            prefs.edit().putInt("sync_rev", SYNC_REV).putLong("last_sync", 0).apply();
        }
        state = restingState();
        if (imported) syncNow();
    }

    private boolean signedIn() {
        return prefs.getString("refresh_token", null) != null && prefs.getString("folder_id", null) != null;
    }

    private String restingState() {
        if (!signedIn()) return "signin";
        return cachedItems().length() > 0 ? "ready" : "empty";
    }

    /** Picks up google-signin.json pushed by the computer. Returns true if a new sign-in was imported. */
    private boolean importSignin() {
        File ext = ctx.getExternalFilesDir(null);
        File f = ext == null ? null : new File(ext, HANDOFF);
        if (f == null || !f.exists()) return false;
        boolean ok = false;
        try (InputStream in = new FileInputStream(f)) {
            JSONObject o = new JSONObject(new String(Http.readAll(in), StandardCharsets.UTF_8));
            String refresh = o.optString("refresh_token", "");
            String folder = o.optString("folder_id", "");
            if (!refresh.isEmpty() && !folder.isEmpty()) {
                boolean newFolder = !folder.equals(prefs.getString("folder_id", ""));
                SharedPreferences.Editor e = prefs.edit()
                        .putString("client_id", o.optString("client_id"))
                        .putString("client_secret", o.optString("client_secret"))
                        .putString("refresh_token", refresh)
                        .putString("folder_id", folder)
                        .putString("folder_name", o.optString("folder_name", "Drive folder"))
                        .remove("access_token").remove("expires_at").putLong("last_sync", 0);
                if (newFolder) e.putString("items", "[]");
                e.apply();
                ok = true;
            }
        } catch (IOException | JSONException ignored) {
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        return ok;
    }

    // ---- status for the UI ----

    String status() {
        if (importSignin()) {
            error = null;
            state = restingState();
            syncNow();
        }
        JSONObject o = new JSONObject();
        try {
            o.put("configured", true);
            o.put("mode", "drive");
            o.put("state", state);
            o.put("folder", prefs.getString("folder_name", null));
            o.put("count", cachedItems().length());
            o.put("done", done);
            o.put("total", total);
            o.put("lastSync", prefs.getLong("last_sync", 0));
            o.put("skipped", new JSONObject(prefs.getString("skipped", "{}")));
            o.put("found", prefs.getInt("found", 0));
            o.put("error", error);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    /**
     * [{id, url, date, place, placeSource}] newest first. placeSource says how sure the place is:
     * "manual" (set from the phone app), "gps" (the photo's own location), "nearby" (estimated from a
     * geotagged photo taken within a few hours), "guess" (estimated from what's in the picture), or "".
     */
    JSONArray cachedItems() {
        JSONArray out = new JSONArray();
        try {
            JSONArray items = new JSONArray(prefs.getString("items", "[]"));
            JSONObject edits = new JSONObject(prefs.getString("placeEdits", "{}"));
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                String name = it.getString("file");
                if (!new File(dir, name).exists()) continue;
                String place = it.optString("place", ""), source = place.isEmpty() ? "" : "gps";
                JSONObject edit = edits.optJSONObject(it.optString("id"));
                if (edit != null) { place = edit.optString("place"); source = edit.optString("source", "manual"); }
                else if (place.isEmpty()) {
                    place = nearbyPlace(items, it);
                    if (!place.isEmpty()) source = "nearby";
                }
                out.put(new JSONObject()
                        .put("id", it.optString("id"))
                        .put("url", "/gphotos/" + name)
                        .put("date", it.optString("date", ""))
                        .put("place", place)
                        .put("placeSource", source)
                        .put("desc", it.optString("desc", ""))
                        .put("title", it.optString("title", ""))
                        .put("device", it.optString("device", ""))
                        .put("lum", it.optInt("lum", -1)));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private static final long NEARBY_MS = 6 * 3_600_000L;

    /** The place of the geotagged photo taken closest in time (within 6 hours), or "". */
    private static String nearbyPlace(JSONArray items, JSONObject it) {
        long t = when(it.optString("date"));
        if (t == 0) return "";
        String best = "";
        long bestGap = NEARBY_MS;
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null || o == it || o.optString("place").isEmpty()) continue;
            long gap = Math.abs(when(o.optString("date")) - t);
            if (gap < bestGap) { bestGap = gap; best = o.optString("place"); }
        }
        return best;
    }

    private static long when(String iso) {
        if (iso == null || iso.length() < 19) return 0;
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(iso.substring(0, 19)).getTime();
        } catch (java.text.ParseException e) {
            return 0;
        }
    }

    /** Set (or with an empty place, clear) a photo's place from the phone app. source: manual | guess. */
    void setPlace(String id, String place, String source) {
        synchronized (this) {
            try {
                JSONObject edits = new JSONObject(prefs.getString("placeEdits", "{}"));
                place = place == null ? "" : place.trim();
                if (place.isEmpty()) edits.remove(id);
                else edits.put(id, new JSONObject().put("place", place.length() > 60 ? place.substring(0, 60) : place)
                        .put("source", "guess".equals(source) ? "guess" : "manual"));
                prefs.edit().putString("placeEdits", edits.toString()).apply();
            } catch (JSONException ignored) {
            }
        }
        changed();
    }

    File file(String name) {
        if (name.contains("/") || name.contains("..")) return null;
        File f = new File(dir, name);
        return f.exists() ? f : null;
    }

    // ---- actions ----

    void syncNow() {
        if (!signedIn() || busy) return;
        busy = true;
        stop = false;
        io.execute(() -> {
            try {
                sync();
            } catch (Exception e) {
                if (!stop) {
                    error = e.getMessage();
                    state = signedIn() ? "error" : "signin";
                    changed();
                }
            } finally {
                busy = false;
            }
        });
    }

    void maybeSync() {
        if (!signedIn()) return;
        if (System.currentTimeMillis() - prefs.getLong("last_sync", 0) < SYNC_EVERY_MS) return;
        syncNow();
    }

    void disconnect() {
        stop = true;
        io.execute(() -> {
            String refresh = prefs.getString("refresh_token", null);
            try {
                if (refresh != null) Http.postForm("https://oauth2.googleapis.com/revoke", "token", refresh);
            } catch (Exception ignored) {
            }
            // Keep the place-name cache; drop everything tied to the account.
            String places = prefs.getString("places", "{}"), edits = prefs.getString("placeEdits", "{}");
            prefs.edit().clear().putString("places", places).putString("placeEdits", edits).apply();
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
                f.delete();
            error = null;
            state = "signin";
            changed();
        });
    }

    // ---- sync ----

    private void sync() throws Exception {
        String folder = prefs.getString("folder_id", "");
        boolean first = cachedItems().length() == 0;

        // 1. List images in the folder.
        List<JSONObject> remote = new ArrayList<>();
        String page = null;
        do {
            String q = "'" + folder + "' in parents and mimeType contains 'image/' and trashed = false";
            String url = DRIVE + "/files?pageSize=1000&q=" + Http.enc(q)
                    + "&fields=" + Http.enc("nextPageToken,files(id,name,mimeType,modifiedTime,createdTime,description,imageMediaMetadata(time,location,cameraMake,cameraModel))")
                    + "&supportsAllDrives=true&includeItemsFromAllDrives=true"
                    + (page != null ? "&pageToken=" + Http.enc(page) : "");
            Http.Resp r = Http.request("GET", url, null, null, accessToken());
            if (r.code == 404) throw new IOException("The Drive folder wasn't found. Run google-signin.sh again to pick it.");
            if (r.code != 200) throw new IOException("Couldn't read the Drive folder (" + r.errorText() + ")");
            JSONObject j = r.json();
            JSONArray files = j.optJSONArray("files");
            if (files != null) for (int i = 0; i < files.length(); i++) remote.add(files.getJSONObject(i));
            page = j.optString("nextPageToken", null);
            if (page != null && page.isEmpty()) page = null;
        } while (page != null && !stop);

        Map<String, JSONObject> sidecars = loadSidecars(folder);

        // Newest photos first (by when they were taken), capped.
        for (JSONObject f : remote) f.put("_date", takenDate(f));
        remote.sort((a, b) -> b.optString("_date").compareTo(a.optString("_date")));
        if (remote.size() > MAX_PHOTOS) remote = new ArrayList<>(remote.subList(0, MAX_PHOTOS));

        // 2. Work out what's new.
        Map<String, JSONObject> cached = new HashMap<>();
        JSONArray old = new JSONArray(prefs.getString("items", "[]"));
        for (int i = 0; i < old.length(); i++) cached.put(old.getJSONObject(i).getString("id"), old.getJSONObject(i));
        JSONObject rejected = new JSONObject(prefs.getString("rejected", "{}"));
        JSONObject stillRejected = new JSONObject();
        int todo = 0;
        for (JSONObject f : remote) {
            if (isRejected(f, rejected)) continue;
            if (needsDownload(f, cached.get(f.getString("id")))) todo++;
        }

        total = todo;
        done = 0;
        if (first || todo > 0) { state = first ? "syncing" : state; changed(); }

        // 3. Download, scale, and name places.
        JSONArray fresh = new JSONArray();
        for (JSONObject f : remote) {
            if (stop) return;
            String id = f.getString("id");
            if (isRejected(f, rejected)) { stillRejected.put(id, rejected.getJSONObject(id)); continue; }
            JSONObject prev = cached.get(id);
            String name = id.replaceAll("[^A-Za-z0-9_-]", "_") + ".jpg";
            JSONObject side = sidecarFor(f.optString("name"), sidecars);
            JSONObject item;
            if (needsDownload(f, prev)) {
                JSONObject exif;
                try {
                    exif = fetchScaled(id, new File(dir, name));
                } catch (Rejected r) {
                    stillRejected.put(id, new JSONObject().put("modified", f.optString("modifiedTime"))
                            .put("reason", r.reason).put("v", META_VERSION));
                    done++;
                    continue;
                } catch (Exception e) {
                    done++;
                    continue; // unreadable or unsupported format; try again next sync
                }
                String place = exif.has("lat") ? placeAt(exif.optDouble("lat"), exif.optDouble("lon")) : placeFor(f);
                item = new JSONObject().put("id", id).put("file", name).put("meta", META_VERSION)
                        .put("modified", f.optString("modifiedTime")).put("exifDate", exif.optString("date", ""))
                        .put("place", place).put("gps", exif.has("lat") || hasLocation(f));
                applySidecar(item, side);
                pickDate(item, f, side);
                done++;
                if (first && done % 5 == 0) { saveItems(fresh, item); changed(); continue; }
            } else {
                item = prev;
                if (item.optString("place").isEmpty() && hasLocation(f)) item.put("place", placeFor(f)).put("gps", true);
                applySidecar(item, side);
                pickDate(item, f, side);
            }
            item.put("title", descriptiveName(f.optString("name")));
            if (!item.has("lum")) item.put("lum", brightness(new File(dir, item.optString("file"))));
            // A Description typed in Drive (Details panel) wins over everything else as the caption.
            String typed = f.optString("description", "").trim();
            if (!typed.isEmpty()) item.put("desc", typed);
            else if (item.optString("desc").isEmpty() || !item.optBoolean("sidecar")) item.remove("desc");
            JSONObject meta = f.optJSONObject("imageMediaMetadata");
            item.put("device", meta == null ? "" : Devices.friendly(meta.optString("cameraMake"), meta.optString("cameraModel")));
            fresh.put(item);
        }

        // 4. Drop photos no longer in the folder.
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (int i = 0; i < fresh.length(); i++) keep.add(fresh.getJSONObject(i).getString("file"));
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) if (!keep.contains(f.getName())) //noinspection ResultOfMethodCallIgnored
            f.delete();

        JSONObject skipped = new JSONObject();
        java.util.Iterator<String> keys = stillRejected.keys();
        while (keys.hasNext()) {
            String reason = stillRejected.getJSONObject(keys.next()).optString("reason", "other");
            skipped.put(reason, skipped.optInt(reason, 0) + 1);
        }
        prefs.edit().putString("items", fresh.toString()).putLong("last_sync", System.currentTimeMillis())
                .putString("rejected", stillRejected.toString()).putString("skipped", skipped.toString())
                .putInt("found", remote.size()).apply();
        int gps = 0, places = 0, dated = 0, matched = 0;
        for (int i = 0; i < fresh.length(); i++) {
            JSONObject it = fresh.getJSONObject(i);
            if (it.optBoolean("gps")) gps++;
            if (!it.optString("place").isEmpty()) places++;
            if (!it.optString("date").isEmpty()) dated++;
            if (it.optBoolean("sidecar")) matched++;
        }
        int withDevice = 0;
        JSONObject sources = new JSONObject();
        for (int i = 0; i < fresh.length(); i++) {
            JSONObject it = fresh.getJSONObject(i);
            if (!it.optString("device").isEmpty()) withDevice++;
            String src = it.optString("dateSource", "?");
            sources.put(src, sources.optInt(src, 0) + 1);
        }
        android.util.Log.i("ClearHome", "Photo dates from: " + sources);
        android.util.Log.i("ClearHome", "Drive sync: " + withDevice + " with device, " + remote.size() + " in folder, " + fresh.length() + " shown, "
                + dated + " dated, " + gps + " with GPS, " + places + " with place names, "
                + sidecars.size() + " Takeout files (" + matched + " matched), skipped " + skipped);
        error = null;
        state = fresh.length() > 0 ? "ready" : "empty";
        changed();
    }

    /** Saves progress mid-download so photos show up while the first sync is still running. */
    private void saveItems(JSONArray fresh, JSONObject latest) throws JSONException {
        fresh.put(latest);
        prefs.edit().putString("items", fresh.toString()).apply();
    }

    private boolean needsDownload(JSONObject remote, JSONObject cached) {
        if (cached == null) return true;
        if (cached.optInt("meta", 1) < META_VERSION) return true;
        if (!remote.optString("modifiedTime").equals(cached.optString("modified"))) return true;
        return !new File(dir, cached.optString("file")).exists();
    }

    /**
     * A file name worth showing as a caption ("Mysore Palace.jpg" -> "Mysore Palace"), or "" for
     * camera/app-generated names like IMG_1234, DSC09503, PXL_2024…, 20260118_000652, Screenshot….
     */
    static String descriptiveName(String fileName) {
        String n = fileName == null ? "" : fileName.replaceAll("\\.[A-Za-z0-9]{2,5}$", "").trim();
        n = n.replaceAll("\\s*\\(\\d+\\)$", "").replaceAll("(?i)-edited$", "").trim();
        if (n.length() < 3) return "";
        String generated = "(?i)^(img|dsc|dscn|dscf|pxl|mvimg|vid|pano|photo|image|screenshot|whatsapp|signal|telegram|snapchat|"
                + "gopr|dji|p\\d{3,}|_dsc|dcim)[\\W_\\d].*|^[\\d\\W_]+$|^[A-Za-z]{0,4}[\\d_-]{6,}.*";
        return n.matches(generated) ? "" : n.replace('_', ' ');
    }

    private static boolean isRejected(JSONObject f, JSONObject rejected) {
        JSONObject r = rejected.optJSONObject(f.optString("id"));
        return r != null && r.optInt("v") == META_VERSION && r.optString("modified").equals(f.optString("modifiedTime"));
    }

    // ---- Google Takeout sidecars ----
    // A Google Photos Takeout export has a JSON file per photo (IMG_1.jpg.json or
    // IMG_1.jpg.supplemental-metadata.json) with the location Google Photos knows,
    // the description, and the time taken. Uploaded next to the photos, they're
    // matched by name.

    private Map<String, JSONObject> loadSidecars(String folder) throws Exception {
        Map<String, JSONObject> byKey = new HashMap<>();
        JSONObject cache = new JSONObject(prefs.getString("sidecars", "{}"));
        JSONObject next = new JSONObject();
        StringBuilder scanLog = new StringBuilder();
        int candidates = 0, jsons = 0, unreadable = 0;
        java.util.Set<String> types = new java.util.TreeSet<>();
        // The info files may sit in the folder itself or in a subfolder (e.g. "TV Photos - Takeout info").
        List<String> parents = new ArrayList<>();
        parents.add(folder);
        Http.Resp sub = Http.request("GET", DRIVE + "/files?pageSize=100&q=" + Http.enc("'" + folder
                + "' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
                + "&fields=" + Http.enc("files(id)") + "&supportsAllDrives=true&includeItemsFromAllDrives=true",
                null, null, accessToken());
        if (sub.code == 200) {
            JSONArray subs = sub.json().optJSONArray("files");
            if (subs != null) for (int i = 0; i < subs.length(); i++) parents.add(subs.getJSONObject(i).getString("id"));
        }
        for (String parent : parents) {
        String page = null;
        do {
            // Drive's name "contains" only matches word prefixes, and uploaded JSON can get various
            // types, so take every non-image, non-folder file and check the name ourselves.
            String q = "'" + parent + "' in parents and not mimeType contains 'image/'"
                    + " and mimeType != 'application/vnd.google-apps.folder' and trashed = false";
            String url = DRIVE + "/files?pageSize=1000&q=" + Http.enc(q)
                    + "&fields=" + Http.enc("nextPageToken,files(id,name,modifiedTime,mimeType)")
                    + "&supportsAllDrives=true&includeItemsFromAllDrives=true"
                    + (page != null ? "&pageToken=" + Http.enc(page) : "");
            Http.Resp r = Http.request("GET", url, null, null, accessToken());
            if (r.code != 200) { scanLog.append(" list ").append(r.code).append(' ').append(r.errorText()); break; }
            JSONObject j = r.json();
            JSONArray files = j.optJSONArray("files");
            if (files != null) for (int i = 0; i < files.length() && !stop; i++) {
                JSONObject f = files.getJSONObject(i);
                candidates++;
                types.add(f.optString("mimeType"));
                if (!f.optString("name").toLowerCase(Locale.ROOT).endsWith(".json")) continue;
                jsons++;
                String id = f.getString("id");
                JSONObject data = cache.optJSONObject(id);
                if (data == null || !data.optString("modified").equals(f.optString("modifiedTime"))) {
                    data = fetchSidecar(id, f.optString("name"), f.optString("modifiedTime"));
                }
                if (data == null) { unreadable++; continue; }
                next.put(id, data);
                for (String k : new String[]{data.optString("title"), data.optString("key")}) {
                    if (!k.isEmpty()) byKey.put(k, data);
                }
            }
            page = j.optString("nextPageToken", null);
            if (page != null && page.isEmpty()) page = null;
        } while (page != null && !stop);
        }
        prefs.edit().putString("sidecars", next.toString()).apply();
        int local = loadLocalSidecars(byKey);
        android.util.Log.i("ClearHome", "Takeout scan: " + local + " sent from the computer, " + parents.size() + " folders, " + candidates + " non-photo files "
                + types + ", " + jsons + " .json, " + unreadable + " unreadable, " + next.length() + " usable" + scanLog);
        return byKey;
    }

    private JSONObject fetchSidecar(String id, String fileName, String modified) {
        try {
            Http.Resp r = Http.request("GET", DRIVE + "/files/" + id + "?alt=media&supportsAllDrives=true", null, null, accessToken());
            if (r.code != 200) {
                android.util.Log.w("ClearHome", "Takeout file " + fileName + ": HTTP " + r.code + " " + r.errorText());
                return null;
            }
            return parseSidecar(r.json(), fileName, modified);
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject parseSidecar(JSONObject j, String fileName, String modified) {
        try {
            if (!j.has("title") && !j.has("geoData")) return null; // not a Takeout photo sidecar
            JSONObject out = new JSONObject().put("modified", modified)
                    .put("title", normalize(j.optString("title")))
                    .put("key", sidecarKey(fileName))
                    .put("desc", j.optString("description", "").trim());
            JSONObject geo = usableGeo(j.optJSONObject("geoData"));
            if (geo == null) geo = usableGeo(j.optJSONObject("geoDataExif"));
            if (geo != null) out.put("lat", geo.optDouble("latitude")).put("lon", geo.optDouble("longitude"));
            JSONObject taken = j.optJSONObject("photoTakenTime");
            if (taken != null && taken.optLong("timestamp", 0) > 0) out.put("taken", taken.optLong("timestamp"));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Takeout info files pushed straight to the TV by takeout-sidecars.sh (no Drive upload needed). */
    private int loadLocalSidecars(Map<String, JSONObject> byKey) {
        File ext = ctx.getExternalFilesDir(null);
        File[] files = ext == null ? null : new File(ext, "takeout").listFiles();
        if (files == null) return 0;
        int n = 0;
        for (File f : files) {
            if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".json")) continue;
            try (InputStream in = new FileInputStream(f)) {
                JSONObject data = parseSidecar(new JSONObject(new String(Http.readAll(in), StandardCharsets.UTF_8)),
                        f.getName(), String.valueOf(f.lastModified()));
                if (data == null) continue;
                for (String k : new String[]{data.optString("title"), data.optString("key")}) {
                    if (!k.isEmpty() && !byKey.containsKey(k)) byKey.put(k, data);
                }
                n++;
            } catch (IOException | JSONException ignored) {
            }
        }
        return n;
    }

    private static JSONObject usableGeo(JSONObject g) {
        if (g == null) return null;
        double lat = g.optDouble("latitude", 0), lon = g.optDouble("longitude", 0);
        return lat == 0 && lon == 0 ? null : g;
    }

    /** "IMG_1.jpg.supplemental-metadata.json" / "IMG_1.jpg(1).json" -> "img_1.jpg" / "img_1(1).jpg". */
    private static String sidecarKey(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".json")) n = n.substring(0, n.length() - 5);
        java.util.regex.Matcher dup = java.util.regex.Pattern.compile("^(.*?)(\\(\\d+\\))$").matcher(n);
        String suffix = "";
        if (dup.matches()) { n = dup.group(1); suffix = dup.group(2); }
        n = n.replaceAll("\\.s[a-z-]*$", ""); // .supplemental-metadata, possibly truncated by Takeout
        if (!suffix.isEmpty()) {
            int dot = n.lastIndexOf('.');
            n = dot > 0 ? n.substring(0, dot) + suffix + n.substring(dot) : n + suffix;
        }
        return n;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).trim();
    }

    /** Finds the sidecar for a photo, allowing for "-edited" copies and Drive's " (1)" duplicates. */
    private static JSONObject sidecarFor(String imageName, Map<String, JSONObject> sidecars) {
        if (sidecars.isEmpty()) return null;
        String n = normalize(imageName);
        String[] tries = {
                n,
                n.replaceAll("-(edited|bearbeitet|modifié|editado)(?=\\.[a-z0-9]+$)", ""),
                n.replaceAll(" \\(\\d+\\)(?=\\.[a-z0-9]+$)", ""),
        };
        for (String t : tries) {
            JSONObject s = sidecars.get(t);
            if (s != null) return s;
        }
        return null;
    }

    /** Fills place/date/description from a Takeout sidecar where the photo itself has none. */
    private void applySidecar(JSONObject item, JSONObject side) throws JSONException {
        if (side == null) return;
        item.put("sidecar", true);
        if (item.optString("place").isEmpty() && side.has("lat")) {
            item.put("place", placeAt(side.optDouble("lat"), side.optDouble("lon"))).put("gps", true);
        }
        if (!side.optString("desc").isEmpty()) item.put("desc", side.optString("desc"));
    }

    /**
     * When the photo was taken, from the most trustworthy source available. Never the upload
     * date: a missing date is better than a wrong one.
     */
    private static void pickDate(JSONObject item, JSONObject f, JSONObject side) throws JSONException {
        String date = "", source = "none";
        if (side != null && side.has("taken")) { // what Google Photos itself shows as the date
            java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            date = iso.format(new java.util.Date(side.optLong("taken") * 1000L));
            source = "takeout";
        }
        if (date.isEmpty()) { date = item.optString("exifDate", ""); source = "exif"; }
        if (date.isEmpty()) { date = takenDateExif(f); source = "drive"; }
        if (date.isEmpty()) { date = dateFromName(f.optString("name")); source = "name"; }
        if (date.isEmpty()) source = "none";
        item.put("date", date).put("dateSource", source);
    }

    /** "20260118_000652.jpg", "IMG_20240314_…", "PXL_2024…", "Screenshot_2024-01-01…" -> ISO date, else "". */
    static String dateFromName(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?<!\\d)((?:19|20)\\d{2})[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\\d|3[01])(?!\\d{3})")
                .matcher(name == null ? "" : name);
        if (!m.find()) return "";
        int year = Integer.parseInt(m.group(1));
        if (year > java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)) return "";
        return m.group(1) + "-" + m.group(2) + "-" + m.group(3) + "T12:00:00";
    }

    /** Drive's EXIF time, falling back to when the file was added to Drive. */
    private static String takenDate(JSONObject f) {
        String t = takenDateExif(f);
        return t.isEmpty() ? f.optString("createdTime", "") : t;
    }

    /** Drive's parsed EXIF time ("2023:03:14 10:22:01") as ISO, or "" if Drive has none (yet). */
    private static String takenDateExif(JSONObject f) {
        JSONObject m = f.optJSONObject("imageMediaMetadata");
        return exifToIso(m == null ? "" : m.optString("time", ""));
    }

    private static String exifToIso(String t) {
        if (t == null || !t.matches("\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}.*")) return "";
        return t.substring(0, 4) + "-" + t.substring(5, 7) + "-" + t.substring(8, 10) + "T" + t.substring(11, 19);
    }

    private static boolean hasLocation(JSONObject f) {
        JSONObject m = f.optJSONObject("imageMediaMetadata");
        return m != null && m.optJSONObject("location") != null;
    }

    /** Downloads the original, saves a ≤1920x1080 JPEG rotated upright, and returns its EXIF {date, lat, lon}. */
    private JSONObject fetchScaled(String id, File dest) throws Exception {
        JSONObject info = new JSONObject();
        File raw = new File(dir, id.replaceAll("[^A-Za-z0-9_-]", "_") + ".orig");
        try {
            Http.download(DRIVE + "/files/" + id + "?alt=media&supportsAllDrives=true", accessToken(), raw);

            int rotate = 0;
            try {
                ExifInterface exif = new ExifInterface(raw.getPath());
                // Only "taken" fields: TAG_DATETIME is the last-modified time (e.g. when exported).
                String when = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
                if (when == null) when = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED);
                String iso = exifToIso(when);
                if (!iso.isEmpty()) info.put("date", iso);
                float[] ll = new float[2];
                if (exif.getLatLong(ll) && !(ll[0] == 0 && ll[1] == 0)) info.put("lat", ll[0]).put("lon", ll[1]);
                int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                rotate = o == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : o == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                        : o == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            } catch (IOException | JSONException ignored) {
            }

            // Quality check 1: shape and resolution, from the header alone (no full decode).
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(raw.getPath(), bounds);
            if (bounds.outWidth <= 0) throw new IOException("unsupported image");
            int ow = rotate % 180 == 0 ? bounds.outWidth : bounds.outHeight;
            int oh = rotate % 180 == 0 ? bounds.outHeight : bounds.outWidth;
            if (ow < oh) throw new Rejected("portrait");
            if ((float) ow / oh < MIN_ASPECT) throw new Rejected("square");
            if (Math.min(ow, oh) < MIN_SHORT_SIDE) throw new Rejected("low resolution");

            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= 1920 || bounds.outHeight / (sample * 2) >= 1080) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bm = BitmapFactory.decodeFile(raw.getPath(), opts);
            if (bm == null) throw new IOException("couldn't decode");

            // Quality check 2: exposure and sharpness, on a small copy.
            String problem = exposureOrBlur(bm);
            if (problem != null) { bm.recycle(); throw new Rejected(problem); }

            float scale = Math.min(1f, Math.max(1920f / (rotate % 180 == 0 ? bm.getWidth() : bm.getHeight()),
                    1080f / (rotate % 180 == 0 ? bm.getHeight() : bm.getWidth())));
            if (rotate != 0 || scale < 1f) {
                Matrix m = new Matrix();
                m.postRotate(rotate);
                m.postScale(scale, scale);
                Bitmap t = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
                if (t != bm) bm.recycle();
                bm = t;
            }
            File tmp = new File(dest.getPath() + ".part");
            try (OutputStream out = new FileOutputStream(tmp)) {
                bm.compress(Bitmap.CompressFormat.JPEG, 88, out);
            } finally {
                bm.recycle();
            }
            if (!tmp.renameTo(dest)) throw new IOException("couldn't save photo");
            return info;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            raw.delete();
        }
    }

    /** Mean brightness 0-255 of a cached photo (for matching photos to the time of day), or -1. */
    private static int brightness(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 16;
        Bitmap bm = BitmapFactory.decodeFile(f.getPath(), o);
        if (bm == null) return -1;
        int w = bm.getWidth(), h = bm.getHeight();
        int[] px = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        bm.recycle();
        double sum = 0;
        for (int c : px) sum += 0.299 * ((c >> 16) & 255) + 0.587 * ((c >> 8) & 255) + 0.114 * (c & 255);
        return (int) Math.round(sum / Math.max(1, px.length));
    }

    /** Returns "too dark", "too bright", "blurry", or null if the photo looks fine. */
    private static String exposureOrBlur(Bitmap bm) {
        int w = 320, h = Math.max(1, Math.round(320f * bm.getHeight() / bm.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(bm, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != bm) small.recycle();
        double[] lum = new double[px.length];
        double sum = 0;
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            lum[i] = 0.299 * ((c >> 16) & 255) + 0.587 * ((c >> 8) & 255) + 0.114 * (c & 255);
            sum += lum[i];
        }
        double mean = sum / px.length;
        if (mean < MIN_BRIGHTNESS) return "too dark";
        if (mean > MAX_BRIGHTNESS) return "too bright";
        // Variance of the Laplacian: low means few sharp edges anywhere in the frame.
        double lsum = 0, lsq = 0;
        int n = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                double l = lum[i - w] + lum[i + w] + lum[i - 1] + lum[i + 1] - 4 * lum[i];
                lsum += l;
                lsq += l * l;
                n++;
            }
        }
        if (n == 0) return null;
        double var = lsq / n - (lsum / n) * (lsum / n);
        return var < MIN_SHARPNESS ? "blurry" : null;
    }

    // ---- place names (OpenStreetMap Nominatim) ----

    private String placeFor(JSONObject f) {
        JSONObject m = f.optJSONObject("imageMediaMetadata");
        JSONObject loc = m == null ? null : m.optJSONObject("location");
        if (loc == null || !loc.has("latitude")) return "";
        return placeAt(loc.optDouble("latitude"), loc.optDouble("longitude"));
    }

    private String placeAt(double lat, double lon) {
        String key = String.format(Locale.US, "%.2f,%.2f", lat, lon); // ~1 km cells
        try {
            JSONObject cache = new JSONObject(prefs.getString("places", "{}"));
            if (cache.has(key)) return cache.getString(key);
            String name = reverseGeocode(lat, lon);
            if (name == null) return "";
            cache.put(key, name);
            prefs.edit().putString("places", cache.toString()).apply();
            return name;
        } catch (JSONException e) {
            return "";
        }
    }

    private String reverseGeocode(double lat, double lon) {
        try {
            long wait = 1100 - (System.currentTimeMillis() - lastGeocode); // Nominatim policy: ≤1 request/second
            if (wait > 0) Thread.sleep(wait);
            lastGeocode = System.currentTimeMillis();
            String url = String.format(Locale.US,
                    "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=12&accept-language=en&lat=%.5f&lon=%.5f", lat, lon);
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            if (c.getResponseCode() != 200) { c.disconnect(); return null; }
            String body;
            try (InputStream in = c.getInputStream()) {
                body = new String(Http.readAll(in), StandardCharsets.UTF_8);
            } finally {
                c.disconnect();
            }
            JSONObject a = new JSONObject(body).optJSONObject("address");
            if (a == null) return null;
            String place = first(a, "city", "town", "village", "suburb", "municipality", "county", "state_district");
            String region = "in".equals(a.optString("country_code")) ? a.optString("state", "") : a.optString("country", "");
            if (place.isEmpty()) return region.isEmpty() ? null : region;
            return region.isEmpty() || region.equals(place) ? place : place + ", " + region;
        } catch (Exception e) {
            return null;
        }
    }

    private static String first(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    // ---- tokens ----

    private String accessToken() throws Exception {
        if (System.currentTimeMillis() < prefs.getLong("expires_at", 0) - 60_000) {
            return prefs.getString("access_token", "");
        }
        String refresh = prefs.getString("refresh_token", null);
        if (refresh == null) throw new IOException("Not signed in. Run google-signin.sh on your computer.");
        Http.Resp r = Http.postForm(TOKEN_URL,
                "client_id", prefs.getString("client_id", ""),
                "client_secret", prefs.getString("client_secret", ""),
                "refresh_token", refresh, "grant_type", "refresh_token");
        if (r.code != 200) {
            if ("invalid_grant".equals(r.json().optString("error"))) {
                prefs.edit().remove("refresh_token").remove("access_token").apply();
                throw new IOException("Google sign-in expired. Run google-signin.sh on your computer again.");
            }
            throw new IOException("Couldn't refresh the Google sign-in (" + r.errorText() + ")");
        }
        JSONObject t = r.json();
        prefs.edit()
                .putString("access_token", t.optString("access_token"))
                .putLong("expires_at", System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000L)
                .apply();
        return prefs.getString("access_token", "");
    }

    private void changed() {
        listener.onChanged();
    }
}
