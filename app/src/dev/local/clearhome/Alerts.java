package dev.local.clearhome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Official disaster alerts for home, from NDMA's SACHET feed (Common Alerting Protocol
 * alerts from IMD, state disaster authorities, etc.). The feed covers India only, so alerts
 * are fetched only when the location chosen in Settings is in India.
 *
 * An alert is shown when it is still in effect and covers home:
 *  - if it names districts, only when the home district is among them (a district list spread
 *    over a state has a centroid/area that says nothing about any one place);
 *  - otherwise (small local alerts such as lightning nowcasts), when home is inside its area.
 */
final class Alerts {
    interface Listener { void onChanged(); }

    private static final String FEED = "https://sachet.ndma.gov.in/cap_public_website/FetchAllAlertDetails";
    private static final long POLL_MS = 15 * 60_000;
    private static final double MARGIN_KM = 5;
    private static final double MAX_RADIUS_KM = 60; // bigger areas are lists of districts, not circles

    private final Place place;
    private double homeLat, homeLon;
    private String[] districtWords = {}; // e.g. "Ernakulam", "Kochi"
    private final Listener listener;
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> poller;
    private volatile String cards = "[]";

    Alerts(Place place, Listener l) {
        this.place = place;
        listener = l;
    }

    /** The location changed: forget alerts for the old one and check again. */
    synchronized void placeChanged() {
        cards = "[]";
        listener.onChanged();
        if (poller != null && !poller.isDone()) {
            stop();
            start();
        }
    }

    /** Takes home from Settings; false when it isn't set or isn't covered by the feed. */
    private boolean loadHome() {
        org.json.JSONObject p = place.get();
        if (p == null || !"IN".equals(p.optString("country"))) return false;
        homeLat = p.optDouble("lat");
        homeLon = p.optDouble("lon");
        java.util.List<String> words = new java.util.ArrayList<>();
        // The district is how most alerts name an area ("Ernakulam District" -> "Ernakulam").
        String district = p.optString("admin2").replaceAll("(?i)\\s+(district|division)$", "").trim();
        if (!district.isEmpty()) words.add(district);
        String name = p.optString("name").trim();
        if (!name.isEmpty() && !name.equalsIgnoreCase(district)) words.add(name);
        districtWords = words.toArray(new String[0]);
        return true;
    }

    synchronized void start() {
        if (poller != null && !poller.isDone()) return;
        poller = io.scheduleWithFixedDelay(this::pollSafely, 0, POLL_MS, TimeUnit.MILLISECONDS);
    }

    synchronized void stop() {
        if (poller != null) poller.cancel(false);
        poller = null;
    }

    /** [{type, severity, color, until, message, area}] most severe first. */
    String cards() { return cards; }

    private void pollSafely() {
        try {
            poll();
        } catch (Throwable t) {
            android.util.Log.w("ClearHome", "Alerts: " + t);
        }
    }

    private void poll() throws Exception {
        if (!loadHome()) {
            if (!cards.equals("[]")) { cards = "[]"; listener.onChanged(); }
            return;
        }
        Http.Resp r = Http.request("GET", FEED, null, null, null);
        if (r.code != 200) throw new java.io.IOException("SACHET HTTP " + r.code);
        JSONArray all = new JSONArray(r.body);
        JSONArray near = new JSONArray();
        long now = System.currentTimeMillis();
        for (int i = 0; i < all.length(); i++) {
            JSONObject a = all.getJSONObject(i);
            long end = parseTime(a.optString("effective_end_time"));
            if (end > 0 && end < now) continue;
            if (!coversHome(a)) continue;
            near.put(new JSONObject()
                    .put("type", a.optString("disaster_type", "Alert"))
                    .put("severity", a.optString("severity", "ALERT"))
                    .put("level", a.optString("severity_level", ""))
                    .put("color", a.optString("severity_color", ""))
                    .put("until", end)
                    .put("area", a.optString("area_description", ""))
                    .put("message", a.optString("warning_message", ""))
                    .put("lang", a.optString("actual_lang", "en"))
                    .put("source", a.optString("alert_source", "")));
        }
        String next = sortBySeverity(near).toString();
        boolean changed = !next.equals(cards);
        cards = next;
        android.util.Log.i("ClearHome", "Alerts: " + all.length() + " in India, " + near.length() + " covering home");
        if (changed) listener.onChanged();
    }

    private boolean coversHome(JSONObject a) {
        String text = (a.optString("warning_message") + " " + a.optString("area_description")).toLowerCase(Locale.ROOT);
        for (String w : districtWords) {
            String lw = w.toLowerCase(Locale.ROOT);
            // District codes like "EKM" must match as whole words; names can match anywhere.
            if (lw.length() <= 4 ? text.matches("(?s).*\\b" + lw + "\\b.*") : text.contains(lw)) return true;
        }
        // Lists districts but not ours ("…districts of Tamil Nadu", "…ജില്ലകളിൽ"): not for home.
        if (text.contains("district") || text.contains("ജില്ല")) return false;
        String[] c = a.optString("centroid", "").split(",");
        if (c.length != 2) return false;
        try {
            double lon = Double.parseDouble(c[0].trim()), lat = Double.parseDouble(c[1].trim());
            double areaKm2 = Double.parseDouble(a.optString("area_covered", "0"));
            double radius = Math.sqrt(Math.max(0, areaKm2) / Math.PI);
            return radius <= MAX_RADIUS_KM && distanceKm(homeLat, homeLon, lat, lon) <= radius + MARGIN_KM;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static JSONArray sortBySeverity(JSONArray in) throws JSONException {
        java.util.List<JSONObject> list = new java.util.ArrayList<>();
        for (int i = 0; i < in.length(); i++) list.add(in.getJSONObject(i));
        list.sort((x, y) -> rank(y.optString("severity")) - rank(x.optString("severity")));
        JSONArray out = new JSONArray();
        for (JSONObject o : list) out.put(o);
        return out;
    }

    private static int rank(String s) {
        switch (s.toUpperCase(Locale.ROOT)) {
            case "WARNING": return 4;
            case "ALERT": return 3;
            case "WATCH": return 2;
            default: return 1;
        }
    }

    /** "Fri Oct 23 13:50:00 IST 2026" -> epoch ms (IST = India). */
    static long parseTime(String s) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("EEE MMM dd HH:mm:ss 'IST' yyyy", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
            Date d = f.parse(s);
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371, dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }
}
