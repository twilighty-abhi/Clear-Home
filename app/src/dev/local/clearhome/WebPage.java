package dev.local.clearhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A WebView showing the Clear Home page, used by both the home screen and the screensaver.
 *
 * The page and app artwork are served from a virtual origin
 * (https://appassets.androidplatform.net), so nothing touches the network:
 *   /index.html, /app.js ...  -> files in app/assets
 *   /icon/<package>, /banner/<package> -> app artwork as PNG
 *   /gphotos/<file>, /traffic.png      -> cached photos and the traffic map
 */
final class WebPage implements Core.Page {
    static final String HOST = "appassets.androidplatform.net";
    static final String ORIGIN = "https://" + HOST;

    private final Context ctx;
    private final Core core;
    final WebView view;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private static final Map<String, byte[]> imageCache = Collections.synchronizedMap(new HashMap<>());

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            imageCache.clear();
            js("ClearHome.appsChanged()");
        }
    };

    WebPage(Context context, String path) {
        ctx = context;
        core = Core.get(context);
        view = new WebView(context);
        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        view.setBackgroundColor(Color.BLACK);
        view.setVerticalScrollBarEnabled(false);
        view.setHorizontalScrollBarEnabled(false);
        view.addJavascriptInterface(new Bridge(), "Android");
        view.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return serve(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return true; // never navigate away
            }
        });
        // Page console -> logcat (tag ClearHome), so problems on the TV can be read with adb.
        view.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                android.util.Log.i("ClearHome", m.messageLevel() + " " + m.message() + " @" + m.sourceId() + ":" + m.lineNumber());
                return true;
            }
        });
        view.setFocusable(true);
        view.loadUrl(ORIGIN + path);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        ctx.registerReceiver(packagesChanged, f);
        core.attach(this);
    }

    @Override
    public void js(final String code) {
        ui.post(() -> view.evaluateJavascript("window.ClearHome&&" + code, null));
    }

    void destroy() {
        core.detach(this);
        try {
            ctx.unregisterReceiver(packagesChanged);
        } catch (IllegalArgumentException ignored) {
        }
        view.destroy();
    }

    // ---- virtual origin ----

    private WebResourceResponse serve(Uri u) {
        if (!HOST.equals(u.getHost())) return null; // real network (weather etc.)
        String path = u.getPath() == null ? "/" : u.getPath();
        try {
            if (path.startsWith("/icon/") || path.startsWith("/banner/") || path.startsWith("/wash/")) {
                String kind = path.substring(1, path.indexOf('/', 1));
                String pkg = path.substring(path.indexOf('/', 1) + 1);
                byte[] png = image(pkg, kind);
                if (png == null) return notFound();
                return response("image/png", new ByteArrayInputStream(png), "max-age=31536000");
            }
            if (path.equals("/traffic.png")) {
                java.io.File f = core.commute.mapFile();
                if (!f.exists()) return notFound();
                return response("image/png", new java.io.FileInputStream(f), "no-cache");
            }
            if (path.equals("/nowplaying.jpg")) {
                byte[] art = core.nowPlaying.art();
                if (art == null) return notFound();
                return response("image/jpeg", new ByteArrayInputStream(art), "max-age=86400");
            }
            if (path.startsWith("/gphotos/")) {
                java.io.File f = core.photos.file(path.substring("/gphotos/".length()));
                if (f == null) return notFound();
                return response("image/jpeg", new java.io.FileInputStream(f), "max-age=31536000");
            }
            String asset = path.equals("/") ? "index.html" : path.substring(1);
            if (asset.contains("..") || asset.startsWith("secrets/")) return notFound();
            return response(mime(asset), ctx.getAssets().open(asset), "no-cache");
        } catch (IOException e) {
            return notFound();
        }
    }

    private static WebResourceResponse response(String mime, InputStream in, String cache) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", cache);
        String enc = mime.startsWith("image/") ? null : "utf-8";
        return new WebResourceResponse(mime, enc, 200, "OK", headers, in);
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }

    private static String mime(String name) {
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".js")) return "application/javascript";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".woff2")) return "font/woff2";
        if (name.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }

    /** kind: "icon", "banner", or "wash" (the banner, or icon, shrunk to 32x18 for a soft backdrop). */
    private byte[] image(String pkg, String kind) {
        String key = kind + ":" + pkg;
        boolean banner = !kind.equals("icon");
        byte[] cached = imageCache.get(key);
        if (cached != null) return cached;

        PackageManager pm = ctx.getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        Drawable d = null;
        try {
            if (banner) {
                if (launch != null) d = pm.getActivityBanner(launch);
                if (d == null) d = pm.getApplicationBanner(pkg);
            } else {
                d = launch != null ? pm.getActivityIcon(launch) : pm.getApplicationIcon(pkg);
            }
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        if (d == null && kind.equals("wash")) {
            try {
                d = launch != null ? pm.getActivityIcon(launch) : pm.getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException e) {
                return null;
            }
        }
        if (d == null) return null;

        // Banners are drawn at 1.5x their nominal 320x180 so they stay sharp on a 1080p panel.
        int w = kind.equals("wash") ? 32 : banner ? 480 : 192;
        int h = kind.equals("wash") ? 18 : banner ? 270 : 192;
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(bm));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.PNG, 100, out);
        bm.recycle();

        byte[] png = out.toByteArray();
        imageCache.put(key, png);
        return png;
    }

    // ---- JavaScript bridge (window.Android) ----

    final class Bridge {
        /** Every launchable app: TV apps first, then phone-style apps flagged tv=false. */
        @JavascriptInterface
        public String getApps() {
            PackageManager pm = ctx.getPackageManager();
            Map<String, JSONObject> out = new LinkedHashMap<>();
            collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, true, out);
            collect(pm, Intent.CATEGORY_LAUNCHER, false, out);
            JSONArray a = new JSONArray();
            for (JSONObject o : out.values()) a.put(o);
            return a.toString();
        }

        private void collect(PackageManager pm, String category, boolean tv, Map<String, JSONObject> out) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                ActivityInfo ai = ri.activityInfo;
                String pkg = ai.packageName;
                if (pkg.equals(ctx.getPackageName()) || out.containsKey(pkg)) continue;
                long version = 0;
                try {
                    version = pm.getPackageInfo(pkg, 0).lastUpdateTime;
                } catch (PackageManager.NameNotFoundException ignored) {
                }
                try {
                    JSONObject o = new JSONObject();
                    o.put("pkg", pkg);
                    o.put("label", ri.loadLabel(pm).toString());
                    o.put("tv", tv);
                    o.put("banner", tv && (ai.banner != 0 || ai.applicationInfo.banner != 0));
                    o.put("v", version);
                    out.put(pkg, o);
                } catch (JSONException ignored) {
                }
            }
        }

        /** Background photos: Google Drive when connected, otherwise the bundled samples. */
        @JavascriptInterface
        public String getPhotos() {
            JSONArray g = core.photos.cachedItems();
            if (g.length() > 0) return g.toString();
            JSONArray a = new JSONArray();
            try {
                String[] files = ctx.getAssets().list("photos");
                if (files != null) {
                    java.util.Arrays.sort(files);
                    for (String f : files) {
                        if (!f.endsWith(".jpg")) continue;
                        a.put(new JSONObject().put("url", "/photos/" + f).put("caption", "Sample photo"));
                    }
                }
            } catch (IOException | JSONException ignored) {
            }
            return a.toString();
        }

        @JavascriptInterface public String getAc() { return core.tapo.cards(); }
        @JavascriptInterface public String getCommute() { return core.commute.card(); }
        @JavascriptInterface public String getAlerts() { return core.alerts.cards(); }
        @JavascriptInterface public String getNotes() { return core.house.notes().toString(); }
        @JavascriptInterface public String getHouse() { return core.house.state().toString(); }
        @JavascriptInterface public void wantCards(boolean ac, boolean traffic) { core.wantCards(ac, traffic); }
        @JavascriptInterface public String getNowPlaying() { return core.nowPlaying.state().toString(); }
        @JavascriptInterface public String notesUrl() { return House.publicUrl(ctx); }
        /** Home location from Settings → Location, or "null" when not chosen yet. */
        @JavascriptInterface public String getPlace() { JSONObject p = core.place.get(); return p == null ? "null" : p.toString(); }
        @JavascriptInterface public boolean setPlace(String json) { return core.place.set(json); }
        @JavascriptInterface public void clearPlace() { core.place.clear(); }
        /** Opens or closes the on-screen keyboard for the focused text field (Settings → Location). */
        @JavascriptInterface
        public void keyboard(final boolean show) {
            ui.post(() -> {
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                        ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm == null) return;
                if (show) {
                    view.requestFocus();
                    imm.showSoftInput(view, 0);
                } else {
                    imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
                }
            });
        }

        @JavascriptInterface public String photosStatus() { return core.photos.status(); }
        @JavascriptInterface public void photosSync() { core.photos.syncNow(); }
        @JavascriptInterface public void photosDisconnect() { core.photos.disconnect(); }

        @JavascriptInterface
        public void launch(final String pkg) {
            ui.post(() -> {
                PackageManager pm = ctx.getPackageManager();
                Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (i == null) i = pm.getLaunchIntentForPackage(pkg);
                if (i == null) {
                    js("ClearHome.toast(\"That app can't be opened\")");
                    return;
                }
                startSafely(i);
            });
        }

        @JavascriptInterface
        public void openTvSettings() {
            ui.post(() -> startSafely(new Intent(Settings.ACTION_SETTINGS)));
        }

        @JavascriptInterface
        public void openAppInfo(String pkg) {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
            ui.post(() -> startSafely(i));
        }

        /** True when the Home button currently resolves to this app. */
        @JavascriptInterface
        public boolean isDefaultHome() {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = ctx.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null && ctx.getPackageName().equals(ri.activityInfo.packageName);
        }

        @JavascriptInterface
        public String version() {
            try {
                return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            } catch (PackageManager.NameNotFoundException e) {
                return "";
            }
        }

        private void startSafely(Intent i) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
            } catch (Exception e) {
                js("ClearHome.toast(\"Couldn't open that\")");
            }
        }
    }
}
