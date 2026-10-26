package dev.local.clearhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * What's playing on the TV (Spotify, YouTube Music, anything with a media session): title,
 * artist, album art, playing or paused. Shown as a small player in the photo-frame view.
 * Needs notification access for MediaWatcher; without it, nothing is reported.
 */
final class NowPlaying {
    interface Listener { void onChanged(); }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<MediaController> watched = new ArrayList<>();
    private MediaSessionManager msm;
    private volatile byte[] art;
    private volatile String artKey = "";
    private volatile int artVersion;
    private volatile String fetching = "";
    private volatile JSONObject current = new JSONObject();

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) { refresh(); }
        @Override public void onMetadataChanged(MediaMetadata m) { refresh(); }
        @Override public void onSessionDestroyed() { main.post(NowPlaying.this::rewatch); }
    };

    NowPlaying(Context c, Listener l) {
        ctx = c;
        listener = l;
    }

    private ComponentName watcher() { return new ComponentName(ctx, MediaWatcher.class); }

    /** Safe to call repeatedly; does nothing until notification access is granted. */
    void start() {
        main.post(() -> {
            if (msm != null) return;
            MediaSessionManager m = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            try {
                m.addOnActiveSessionsChangedListener(list -> rewatch(), watcher(), main);
            } catch (SecurityException e) {
                return; // no notification access yet
            }
            msm = m;
            rewatch();
        });
    }

    private void rewatch() {
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
        try {
            watched.addAll(msm.getActiveSessions(watcher()));
        } catch (SecurityException e) {
            return;
        }
        for (MediaController c : watched) c.registerCallback(callback, main);
        refresh();
    }

    /** {playing, title, artist, album, app, art ("/nowplaying.jpg?<key>" or ""), position, duration, at} or {} */
    JSONObject state() {
        // Re-read the current song (cheap): change callbacks can be missed while we're in the background.
        // Players are only re-registered when one starts or stops (the sessions listener).
        main.post(() -> { if (msm != null) refresh(); });
        return current;
    }

    byte[] art() { return art; }

    private void refresh() {
        MediaController best = null;
        for (MediaController c : watched) {
            PlaybackState s = c.getPlaybackState();
            if (c.getMetadata() == null || s == null) continue;
            if (s.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
            if (best == null && s.getState() == PlaybackState.STATE_PAUSED) best = c;
        }
        JSONObject o = new JSONObject();
        try {
            if (best != null) {
                MediaMetadata m = best.getMetadata();
                PlaybackState s = best.getPlaybackState();
                String title = m.getString(MediaMetadata.METADATA_KEY_TITLE);
                if (title == null || title.isEmpty()) title = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
                String artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (artist == null || artist.isEmpty()) artist = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
                String album = m.getString(MediaMetadata.METADATA_KEY_ALBUM);
                String key = (title + "|" + artist + "|" + album).hashCode() + "";
                if (!key.equals(artKey)) {
                    artKey = key;
                    art = null;
                    artVersion++;
                }
                if (art == null) {
                    // The art often arrives in a later update for the same song (Spotify), so keep looking.
                    art = jpeg(m);
                    if (art != null) artVersion++;
                    else fetchArt(m, key); // some apps only give a link to the art
                }
                o.put("playing", s.getState() == PlaybackState.STATE_PLAYING)
                        .put("title", title == null ? "" : title).put("artist", artist == null ? "" : artist)
                        .put("album", album == null ? "" : album).put("app", label(best.getPackageName()))
                        .put("pkg", best.getPackageName())
                        .put("art", art == null ? "" : "/nowplaying.jpg?" + key + "-" + artVersion)
                        .put("position", s.getPosition()).put("speed", s.getPlaybackSpeed())
                        .put("duration", m.getLong(MediaMetadata.METADATA_KEY_DURATION))
                        .put("at", s.getLastPositionUpdateTime() > 0 ? System.currentTimeMillis()
                                - (android.os.SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) : System.currentTimeMillis());
            }
        } catch (JSONException ignored) {
        }
        boolean changed = !same(o, current);
        if (changed) android.util.Log.i("ClearHome", "Now playing: " + o.optString("title") + " playing=" + o.optBoolean("playing")
                + " position=" + o.optLong("position") + " duration=" + o.optLong("duration") + " art=" + !o.optString("art").isEmpty());
        current = o;
        if (changed) listener.onChanged();
    }

    /** Loads the art from the metadata's link (https or content://) in the background, then refreshes. */
    private void fetchArt(MediaMetadata m, String key) {
        String uri = null;
        for (String k : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
            String v = m.getString(k);
            if (v != null && !v.isEmpty()) { uri = v; break; }
        }
        if (uri == null || key.equals(fetching)) return;
        fetching = key;
        String link = uri;
        new Thread(() -> {
            try (java.io.InputStream in = link.startsWith("http")
                    ? new java.net.URL(link).openStream()
                    : ctx.getContentResolver().openInputStream(android.net.Uri.parse(link))) {
                Bitmap b = android.graphics.BitmapFactory.decodeStream(in);
                byte[] jpg = b == null ? null : scaled(b);
                if (jpg != null && key.equals(artKey) && art == null) {
                    art = jpg;
                    artVersion++;
                    current = new JSONObject();
                    main.post(this::refresh);
                }
            } catch (Exception e) {
                android.util.Log.w("ClearHome", "Now playing art: " + e);
            }
        }, "now-playing-art").start();
    }

    /**
     * Same song and state? Position timestamps shift by a millisecond on every read, so they only count
     * when the position really jumped (a seek), not on every update; otherwise the player redraws constantly.
     */
    private static boolean same(JSONObject a, JSONObject b) {
        for (String k : new String[]{"title", "artist", "album", "app", "art", "playing", "duration"})
            if (!a.optString(k).equals(b.optString(k))) return false;
        long now = System.currentTimeMillis();
        double ea = a.optLong("position") + (a.optBoolean("playing") ? (now - a.optLong("at")) * a.optDouble("speed", 1) : 0);
        double eb = b.optLong("position") + (b.optBoolean("playing") ? (now - b.optLong("at")) * b.optDouble("speed", 1) : 0);
        return Math.abs(ea - eb) < 2000;
    }

    private static byte[] jpeg(MediaMetadata m) {
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return b == null ? null : scaled(b);
    }

    private static byte[] scaled(Bitmap b) {
        Bitmap small = Bitmap.createScaledBitmap(b, 300, Math.max(1, b.getHeight() * 300 / Math.max(1, b.getWidth())), true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 88, out);
        return out.toByteArray();
    }

    private String label(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
