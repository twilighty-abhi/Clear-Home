package dev.local.clearhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Where home is, chosen in Settings → Location on the TV:
 * {name, lat, lon, country (ISO code, e.g. "IN"), admin1 (state), admin2 (district/county)}.
 * Weather and air quality, official alerts and the traffic map all read it. Null until set.
 */
final class Place {
    interface Listener { void onChanged(); }

    private final SharedPreferences prefs;
    private final Listener listener;

    Place(Context c, Listener l) {
        prefs = c.getSharedPreferences("place", Context.MODE_PRIVATE);
        listener = l;
    }

    JSONObject get() {
        try {
            JSONObject o = new JSONObject(prefs.getString("place", "{}"));
            return o.has("lat") && o.has("lon") ? o : null;
        } catch (JSONException e) {
            return null;
        }
    }

    /** Stores a place from the settings screen; returns false if it isn't usable. */
    boolean set(String json) {
        try {
            JSONObject in = new JSONObject(json);
            double lat = in.getDouble("lat"), lon = in.getDouble("lon");
            if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return false;
            JSONObject o = new JSONObject()
                    .put("name", clip(in.optString("name", ""), 60))
                    .put("lat", lat).put("lon", lon)
                    .put("country", clip(in.optString("country", ""), 2).toUpperCase(java.util.Locale.ROOT))
                    .put("admin1", clip(in.optString("admin1", ""), 60))
                    .put("admin2", clip(in.optString("admin2", ""), 60));
            prefs.edit().putString("place", o.toString()).apply();
        } catch (JSONException e) {
            return false;
        }
        listener.onChanged();
        return true;
    }

    void clear() {
        prefs.edit().remove("place").apply();
        listener.onChanged();
    }

    private static String clip(String s, int n) {
        s = s.trim();
        return s.length() > n ? s.substring(0, n) : s;
    }
}
