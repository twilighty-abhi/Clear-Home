package dev.local.clearhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Timers set from the phone app. The dashboard draws them; this class does what the page can't:
 * a chime when one ends, and for timers marked "Show over other apps", a small countdown pill
 * over Netflix & co. plus a "done" alert. Those are plain native views (cheap), and need the
 * "display over other apps" permission:
 *   adb shell appops set dev.local.clearhome SYSTEM_ALERT_WINDOW allow
 */
final class Timers {
    interface Source { JSONArray timers(); }
    interface Visible { boolean clearHomeOnScreen(); }

    private final Context ctx;
    private final Source source;
    private final Visible visible;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> finished = new HashSet<>();
    private TextView pill;
    private View alert;
    private String alertId = "";
    private boolean ticking;

    Timers(Context c, Source s, Visible v) {
        ctx = c;
        source = s;
        visible = v;
    }

    /** Call when the flat's state changes; ticks every second while any timer exists. */
    void changed() {
        main.post(() -> {
            if (!ticking) {
                ticking = true;
                tick();
            }
        });
    }

    private void tick() {
        JSONArray ts = source.timers();
        long now = System.currentTimeMillis();
        boolean onScreen = visible.clearHomeOnScreen();
        JSONObject pillFor = null;
        Set<String> ids = new HashSet<>();
        for (int i = 0; ts != null && i < ts.length(); i++) {
            JSONObject t = ts.optJSONObject(i);
            if (t == null) continue;
            String id = t.optString("id");
            ids.add(id);
            boolean paused = t.optBoolean("paused");
            long left = paused ? t.optLong("left") : Math.max(0, t.optLong("ends") - now);
            boolean important = t.optBoolean("important");
            if (!paused && left == 0) {
                if (finished.add(id) && now - t.optLong("ends") < 60_000) { // just ended (not one that ended long ago)
                    if (onScreen || important) chime(important ? 3 : 1);
                    if (important && !onScreen) showAlert(id, t.optString("label"));
                }
            } else {
                finished.remove(id);
                if (important && !onScreen && (pillFor == null || left < leftOf(pillFor, now))) pillFor = t;
            }
        }
        finished.retainAll(ids);
        if (alert != null && (!ids.contains(alertId) || onScreen)) hideAlert();
        if (pillFor != null && alert == null) showPill(pillFor.optString("label"), leftOf(pillFor, now), pillFor.optBoolean("paused"));
        else hidePill();
        if (ids.isEmpty() && alert == null) { ticking = false; return; }
        main.postDelayed(this::tick, 1000);
    }

    private static long leftOf(JSONObject t, long now) {
        return t.optBoolean("paused") ? t.optLong("left") : Math.max(0, t.optLong("ends") - now);
    }

    static String clock(long ms) {
        long s = (ms + 999) / 1000, h = s / 3600, m = s % 3600 / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec) : String.format(Locale.US, "%d:%02d", m, sec);
    }

    // ---------- over other apps ----------

    private boolean canOverlay() { return Settings.canDrawOverlays(ctx); }

    private WindowManager.LayoutParams params(int gravity) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        lp.x = dp(36);
        lp.y = dp(28);
        return lp;
    }

    private GradientDrawable glass(int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.argb(215, 16, 16, 20));
        g.setCornerRadius(dp(22));
        g.setStroke(dp(1.5f), stroke);
        return g;
    }

    private void showPill(String label, long left, boolean paused) {
        if (!canOverlay()) return;
        String text = "⏱  " + (label.isEmpty() ? "Timer" : label) + "   " + clock(left) + (paused ? "  ❚❚" : "");
        if (pill == null) {
            pill = new TextView(ctx);
            pill.setTextColor(Color.WHITE);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(15));
            pill.setPadding(dp(16), dp(8), dp(16), dp(8));
            pill.setBackground(glass(Color.argb(60, 255, 255, 255)));
            pill.setFontFeatureSettings("tnum");
            try {
                wm().addView(pill, params(Gravity.TOP | Gravity.END));
            } catch (RuntimeException e) {
                pill = null;
                return;
            }
        }
        pill.setText(text);
    }

    private void hidePill() {
        if (pill == null) return;
        try { wm().removeView(pill); } catch (RuntimeException ignored) { }
        pill = null;
    }

    private void showAlert(String id, String label) {
        if (!canOverlay()) return;
        hidePill();
        hideAlert();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(28), dp(18), dp(28), dp(18));
        box.setBackground(glass(Color.rgb(255, 159, 10)));
        TextView title = new TextView(ctx);
        title.setText("⏱  " + (label.isEmpty() ? "Timer" : label));
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(22));
        TextView sub = new TextView(ctx);
        sub.setText("Time's up");
        sub.setTextColor(Color.rgb(255, 159, 10));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(16));
        box.addView(title);
        box.addView(sub);
        try {
            wm().addView(box, params(Gravity.TOP | Gravity.END));
        } catch (RuntimeException e) {
            return;
        }
        alert = box;
        alertId = id;
        main.postDelayed(() -> { if (alert == box) hideAlert(); }, 45_000); // goes away by itself
    }

    private void hideAlert() {
        if (alert == null) return;
        try { wm().removeView(alert); } catch (RuntimeException ignored) { }
        alert = null;
        alertId = "";
    }

    private WindowManager wm() { return (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE); }

    private int dp(float v) { return Math.round(v * ctx.getResources().getDisplayMetrics().density); }

    // ---------- chime: two soft bell notes, synthesised (no sound files) ----------

    private void chime(int times) {
        new Thread(() -> {
            int rate = 44100;
            double[][] notes = {{1318.5, 0.0}, {1046.5, 0.18}}; // E6 then C6
            int len = (int) (rate * 0.9);
            short[] pcm = new short[len * times];
            for (int r = 0; r < times; r++) {
                for (double[] n : notes) {
                    int start = r * len + (int) (n[1] * rate);
                    for (int i = 0; start + i < (r + 1) * len; i++) {
                        double t = i / (double) rate, env = Math.exp(-5.0 * t) * Math.min(1, t * 200);
                        double v = env * (Math.sin(2 * Math.PI * n[0] * t) + 0.3 * Math.sin(4 * Math.PI * n[0] * t)) * 0.22;
                        int k = start + i;
                        pcm[k] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, pcm[k] + v * Short.MAX_VALUE));
                    }
                }
            }
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.length * 2).setTransferMode(AudioTrack.MODE_STATIC).build();
            track.write(pcm, 0, pcm.length);
            track.play();
            try { Thread.sleep(pcm.length * 1000L / rate + 200); } catch (InterruptedException ignored) { }
            track.release();
        }, "timer-chime").start();
    }
}
