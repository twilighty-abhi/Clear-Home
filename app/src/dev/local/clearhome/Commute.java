package dev.local.clearhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Traffic. Modes, chosen by commute.json:
 *  - "mapbox_token" + "area": a small dark map of the area with live traffic lines (Mapbox
 *    Static Images, navigation-night style), downloaded and served from app storage.
 * TomTom (free tier, no card):
 *  - "area": live speeds on the roads around home vs. their free-flow speeds (Traffic Flow
 *    API, a few sample points) plus current slowdowns nearby (Traffic Incidents API).
 *  - "from"/"to": drive time with live traffic (Routing API).
 * Polls every 10 minutes while the home screen is in front (~6 requests a poll).
 *
 * Config (API key, from/to coordinates and names) arrives as commute.json in the app's
 * external files dir, pushed by commute-setup.sh; it's moved into private storage and
 * the file is deleted.
 */
final class Commute {
    interface Listener { void onChanged(); }

    private static final long POLL_MS = 10 * 60_000;
    // Sample points around home: centre plus ~1.5 km N/E/S/W (flow data snaps to the nearest road).
    private static final double[][] OFFSETS = {{0, 0}, {0.0135, 0}, {0, 0.0137}, {-0.0135, 0}, {0, -0.0137}};

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Listener listener;
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> poller;
    private volatile JSONObject latest; // card data, or null

    Commute(Context c, Listener l) {
        ctx = c;
        listener = l;
        prefs = c.getSharedPreferences("commute", Context.MODE_PRIVATE);
        importConfig();
    }

    private void importConfig() {
        File ext = ctx.getExternalFilesDir(null);
        File f = ext == null ? null : new File(ext, "commute.json");
        if (f == null || !f.exists()) return;
        try (InputStream in = new FileInputStream(f)) {
            JSONObject o = new JSONObject(new String(Http.readAll(in), StandardCharsets.UTF_8));
            prefs.edit().putString("config", o.toString()).apply();
            latest = null;
            android.util.Log.i("ClearHome", "Commute: imported route to " + o.optJSONObject("to"));
        } catch (IOException | JSONException e) {
            android.util.Log.w("ClearHome", "Commute: couldn't read commute.json: " + e);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private JSONObject config() {
        try {
            JSONObject c = new JSONObject(prefs.getString("config", "{}"));
            return c.optString("tomtom_key", "").isEmpty() && c.optString("mapbox_token", "").isEmpty() ? null : c;
        } catch (JSONException e) {
            return null;
        }
    }

    synchronized void start() {
        importConfig();
        if (config() == null || (poller != null && !poller.isDone())) return;
        poller = io.scheduleWithFixedDelay(this::pollSafely, 0, POLL_MS, TimeUnit.MILLISECONDS);
    }

    synchronized void stop() {
        if (poller != null) poller.cancel(false);
        poller = null;
    }

    /** {to, minutes, typical, delay, level, distanceKm} or "null" when not set up / no data. */
    String card() {
        JSONObject l = latest;
        return l == null ? "null" : l.toString();
    }

    /** The traffic map image, refreshed in app storage and served at /traffic.png. */
    File mapFile() { return new File(ctx.getFilesDir(), "traffic.png"); }

    private void pollMap(JSONObject c) throws Exception {
        JSONObject area = c.getJSONObject("area");
        double lat = area.getDouble("lat"), lon = area.getDouble("lon");
        String url = String.format(Locale.US,
                "https://api.mapbox.com/styles/v1/mapbox/navigation-night-v1/static/pin-s+ffffff(%.6f,%.6f)/%.6f,%.6f,13.5,0/520x300@2x"
                        + "?access_token=%s&attribution=false&logo=false",
                lon, lat, lon, lat, Http.enc(c.getString("mapbox_token")));
        try {
            Http.download(url, null, mapFile());
        } catch (IOException e) {
            if (!mapFile().exists()) throw new IOException("Mapbox map failed: " + e.getMessage());
            android.util.Log.w("ClearHome", "Traffic map refresh failed, keeping the last one: " + e.getMessage());
            return;
        }
        latest = new JSONObject()
                .put("mode", "map")
                .put("area", area.optString("name", "Home"))
                .put("url", "/traffic.png?t=" + System.currentTimeMillis())
                .put("updated", System.currentTimeMillis());
        android.util.Log.i("ClearHome", "Traffic map updated (" + mapFile().length() / 1024 + " KB)");
        listener.onChanged();
    }

    /** Area traffic: speed ratio on nearby roads (weighted toward faster/major roads) + slowdowns. */
    private void pollArea(JSONObject c) throws Exception {
        JSONObject area = c.getJSONObject("area");
        double lat = area.getDouble("lat"), lon = area.getDouble("lon");
        String key = Http.enc(c.getString("tomtom_key"));
        double weighted = 0, weights = 0;
        int closures = 0, sampled = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (double[] o : OFFSETS) {
            String url = String.format(Locale.US,
                    "https://api.tomtom.com/traffic/services/4/flowSegmentData/absolute/10/json?point=%.5f,%.5f&unit=KMPH&key=%s",
                    lat + o[0], lon + o[1], key);
            Http.Resp r = Http.request("GET", url, null, null, null);
            if (r.code != 200) {
                if (r.code == 403 || r.code == 401) throw new IOException("TomTom key rejected (HTTP " + r.code + ")");
                continue;
            }
            JSONObject f = r.json().optJSONObject("flowSegmentData");
            if (f == null) continue;
            // Two sample points can snap to the same road segment; count each segment once.
            JSONObject coords = f.optJSONObject("coordinates");
            String seg = coords == null ? String.valueOf(sampled) : String.valueOf(coords.optJSONArray("coordinate"));
            if (!seen.add(seg.length() > 120 ? seg.substring(0, 120) : seg)) continue;
            double cur = f.optDouble("currentSpeed", 0), free = f.optDouble("freeFlowSpeed", 0);
            if (f.optBoolean("roadClosure")) closures++;
            if (free <= 0) continue;
            weighted += Math.min(1.0, cur / free) * free;
            weights += free;
            sampled++;
        }
        if (sampled == 0) throw new IOException("no road speed data near home");
        double ratio = weighted / weights;

        // Slowdowns within ~3 km: jams, accidents, closures, roadworks with real delay.
        int slowdowns = closures;
        String bbox = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", lon - 0.028, lat - 0.027, lon + 0.028, lat + 0.027);
        Http.Resp inc = Http.request("GET", "https://api.tomtom.com/traffic/services/5/incidentDetails?bbox=" + bbox
                + "&fields=" + Http.enc("{incidents{properties{iconCategory,magnitudeOfDelay}}}")
                + "&language=en-GB&timeValidityFilter=present&key=" + key, null, null, null);
        if (inc.code == 200) {
            JSONArray list = inc.json().optJSONArray("incidents");
            if (list != null) for (int i = 0; i < list.length(); i++) {
                JSONObject p = list.getJSONObject(i).optJSONObject("properties");
                if (p != null && p.optInt("magnitudeOfDelay", 0) >= 2) slowdowns++;
            }
        }

        String level = ratio >= 0.85 ? "light" : ratio >= 0.6 ? "moderate" : "heavy";
        latest = new JSONObject()
                .put("mode", "area")
                .put("area", area.optString("name", "Nearby"))
                .put("speedPct", Math.round(ratio * 100))
                .put("level", level)
                .put("slowdowns", slowdowns)
                .put("roads", sampled);
        android.util.Log.i("ClearHome", "Traffic: " + latest);
        listener.onChanged();
    }

    private void pollSafely() {
        try {
            poll();
        } catch (Throwable t) {
            android.util.Log.w("ClearHome", "Commute: " + t);
        }
    }

    private void poll() throws Exception {
        JSONObject c = config();
        if (c == null) return;
        if (!c.optString("mapbox_token", "").isEmpty()) { pollMap(c); return; }
        if (c.has("area")) { pollArea(c); return; }
        JSONObject from = c.getJSONObject("from"), to = c.getJSONObject("to");
        String url = String.format(Locale.US,
                "https://api.tomtom.com/routing/1/calculateRoute/%.6f,%.6f:%.6f,%.6f/json"
                        + "?key=%s&traffic=true&departAt=now&computeTravelTimeFor=all&routeRepresentation=summaryOnly",
                from.getDouble("lat"), from.getDouble("lon"), to.getDouble("lat"), to.getDouble("lon"),
                Http.enc(c.getString("tomtom_key")));
        Http.Resp r = Http.request("GET", url, null, null, null);
        if (r.code != 200) throw new IOException("TomTom HTTP " + r.code + " " + r.body.substring(0, Math.min(160, r.body.length())));
        JSONArray routes = r.json().optJSONArray("routes");
        if (routes == null || routes.length() == 0) throw new IOException("no route");
        JSONObject sum = routes.getJSONObject(0).getJSONObject("summary");

        int live = sum.getInt("travelTimeInSeconds");
        int empty = sum.optInt("noTrafficTravelTimeInSeconds", live);
        int usual = sum.optInt("historicTrafficTravelTimeInSeconds", empty);
        double ratio = empty > 0 ? (double) live / empty : 1;
        String level = ratio < 1.15 ? "light" : ratio < 1.4 ? "moderate" : "heavy";

        latest = new JSONObject()
                .put("to", to.optString("name", "Work"))
                .put("minutes", Math.round(live / 60f))
                .put("typical", Math.round(usual / 60f))
                .put("delay", Math.round(sum.optInt("trafficDelayInSeconds", 0) / 60f))
                .put("level", level)
                .put("distanceKm", Math.round(sum.optInt("lengthInMeters", 0) / 100f) / 10.0);
        android.util.Log.i("ClearHome", "Commute: " + latest);
        listener.onChanged();
    }
}
