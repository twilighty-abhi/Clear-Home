package dev.local.clearhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reads Tapo P110 smart plugs on the local network (power, units today, running time).
 *
 * Uses Tapo's local KLAP protocol, which the plugs accept once "Third-Party
 * Compatibility" is on in the Tapo app. Plugs are found by MAC address with the
 * same UDP discovery the Tapo app uses, so DHCP address changes don't matter.
 *
 * The Tapo login and plug names arrive as tapo.json in the app's external files
 * dir (pushed by tapo-signin.sh); they're moved into private storage and the file
 * is deleted. Polling runs only while the home screen is in front.
 */
final class Tapo {
    interface Listener { void onChanged(); }

    private static final long POLL_MS = 60_000;
    private static final byte[] DISCOVERY = hex("020000010000000000000000463cb5d3");

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Listener listener;
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Session> sessions = new HashMap<>(); // by MAC
    private final Map<String, JSONObject> readings = new HashMap<>(); // by MAC
    private ScheduledFuture<?> poller;
    private volatile String error;

    Tapo(Context c, Listener l) {
        ctx = c;
        listener = l;
        prefs = c.getSharedPreferences("tapo", Context.MODE_PRIVATE);
        importConfig();
    }

    private boolean configured() {
        return !prefs.getString("plugs", "[]").equals("[]");
    }

    private void importConfig() {
        File ext = ctx.getExternalFilesDir(null);
        File f = ext == null ? null : new File(ext, "tapo.json");
        if (f == null || !f.exists()) return;
        try (InputStream in = new FileInputStream(f)) {
            JSONObject o = new JSONObject(new String(Http.readAll(in), StandardCharsets.UTF_8));
            prefs.edit()
                    .putString("email", o.optString("email", ""))
                    .putString("password", o.optString("password", ""))
                    .putString("plugs", o.optJSONArray("plugs") == null ? "[]" : o.getJSONArray("plugs").toString())
                    .apply();
            android.util.Log.i("ClearHome", "Tapo: imported logins for " + (o.optJSONArray("plugs") == null ? 0
                    : o.getJSONArray("plugs").length()) + " plugs");
            synchronized (sessions) { sessions.clear(); }
        } catch (IOException | JSONException e) {
            android.util.Log.w("ClearHome", "Tapo: couldn't read tapo.json: " + e);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    // ---- lifecycle ----

    synchronized void start() {
        importConfig();
        if (!configured()) { android.util.Log.i("ClearHome", "Tapo: no plugs configured"); return; }
        if (poller != null && !poller.isDone()) return;
        android.util.Log.i("ClearHome", "Tapo: polling plugs");
        poller = io.scheduleWithFixedDelay(this::pollAll, 0, POLL_MS, TimeUnit.MILLISECONDS);
    }

    synchronized void stop() {
        if (poller != null) poller.cancel(false);
        poller = null;
    }

    /** [{name, on, watts, kwhToday, kwhMonth, runMinutes, ok}] in the configured order. */
    String cards() {
        JSONArray out = new JSONArray();
        try {
            JSONArray plugs = new JSONArray(prefs.getString("plugs", "[]"));
            for (int i = 0; i < plugs.length(); i++) {
                JSONObject p = plugs.getJSONObject(i);
                JSONObject r;
                synchronized (readings) { r = readings.get(mac(p.optString("mac"))); }
                JSONObject card = new JSONObject().put("name", p.optString("name", "AC"));
                if (r != null) {
                    card.put("ok", true).put("on", r.optBoolean("on"))
                            .put("watts", r.optDouble("watts"))
                            .put("kwhToday", r.optDouble("kwhToday"))
                            .put("kwhMonth", r.optDouble("kwhMonth"))
                            .put("runMinutes", r.optInt("runMinutes"));
                } else {
                    card.put("ok", false);
                }
                out.put(card);
            }
        } catch (JSONException ignored) {
        }
        return out.toString();
    }

    // ---- polling ----

    private void pollAll() {
        try {
            pollAllUnsafe();
        } catch (Throwable t) { // a scheduled task that throws is silently cancelled, so never let it
            android.util.Log.e("ClearHome", "Tapo poll crashed: " + t);
        }
    }

    private void pollAllUnsafe() {
        try {
            JSONArray plugs = new JSONArray(prefs.getString("plugs", "[]"));
            Map<String, String> ips = null;
            for (int i = 0; i < plugs.length(); i++) {
                JSONObject p = plugs.getJSONObject(i);
                String mac = mac(p.optString("mac"));
                String ip = p.optString("ip", "");
                Session s;
                synchronized (sessions) { s = sessions.get(mac); }
                try {
                    if (s == null) {
                        if (ips == null) {
                            ips = discover();
                            android.util.Log.i("ClearHome", "Tapo: found plugs " + ips);
                        }
                        if (ips.containsKey(mac)) ip = ips.get(mac);
                        // Each plug can belong to a different Tapo account; fall back to the shared login.
                        s = new Session(ip, p.optString("email", prefs.getString("email", "")),
                                p.optString("password", prefs.getString("password", "")));
                        synchronized (sessions) { sessions.put(mac, s); }
                    }
                    JSONObject reading = read(s);
                    synchronized (readings) { readings.put(mac, reading); }
                    error = null;
                } catch (Exception e) {
                    synchronized (sessions) { sessions.remove(mac); } // rediscover and log in again next time
                    synchronized (readings) { readings.remove(mac); }
                    error = p.optString("name") + ": " + e.getMessage();
                    android.util.Log.w("ClearHome", "Tapo " + error);
                }
            }
            listener.onChanged();
        } catch (JSONException ignored) {
        }
    }

    private static JSONObject read(Session s) throws Exception {
        JSONObject info = s.call("get_device_info");
        JSONObject energy = s.call("get_energy_usage");
        boolean on = info.optBoolean("device_on", false);
        // current_power is in milliwatts on P110 energy usage; Wh for energies; minutes for runtimes.
        double watts = energy.optDouble("current_power", 0) / 1000.0;
        JSONObject r = new JSONObject()
                .put("on", on)
                .put("watts", on ? watts : 0)
                .put("kwhToday", energy.optDouble("today_energy", 0) / 1000.0)
                .put("kwhMonth", energy.optDouble("month_energy", 0) / 1000.0)
                .put("runMinutes", energy.optInt("today_runtime", 0));
        android.util.Log.i("ClearHome", String.format(Locale.US, "Tapo %s: %s, %.0f W, %.2f kWh today, %d min",
                s.ip, on ? "on" : "off", r.optDouble("watts"), r.optDouble("kwhToday"), r.optInt("runMinutes")));
        return r;
    }

    /** MAC (as "AA-BB-CC-DD-EE-FF") -> IP, via the Tapo UDP discovery broadcast. */
    private static Map<String, String> discover() {
        Map<String, String> found = new HashMap<>();
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setBroadcast(true);
            sock.setSoTimeout(800);
            InetAddress bcast = InetAddress.getByName("255.255.255.255");
            long end = System.currentTimeMillis() + 3000;
            long nextSend = 0;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextSend) {
                    sock.send(new DatagramPacket(DISCOVERY, DISCOVERY.length, bcast, 20002));
                    nextSend = System.currentTimeMillis() + 1000;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (p.getLength() <= 16) continue;
                try {
                    JSONObject res = new JSONObject(new String(buf, 16, p.getLength() - 16, StandardCharsets.UTF_8))
                            .optJSONObject("result");
                    if (res != null) found.put(mac(res.optString("mac")), p.getAddress().getHostAddress());
                } catch (JSONException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return found;
    }

    private static String mac(String m) {
        return m == null ? "" : m.toUpperCase(Locale.ROOT).replace(':', '-');
    }

    // ---- KLAP session ----

    private final class Session {
        final String ip;
        private final String email, password;
        private byte[] key, ivBase, sig;
        private int seq;
        private String cookie;

        Session(String ip, String email, String password) throws Exception {
            this.ip = ip;
            this.email = email;
            this.password = password;
            handshake();
        }

        private void handshake() throws Exception {
            byte[] auth = sha256(concat(sha1(email.getBytes(StandardCharsets.UTF_8)),
                    sha1(password.getBytes(StandardCharsets.UTF_8))));
            byte[] local = new byte[16];
            new SecureRandom().nextBytes(local);

            Resp h1 = post("/app/handshake1", local, null);
            if (h1.code != 200 || h1.body.length < 48) throw new IOException("handshake failed (HTTP " + h1.code + ")");
            cookie = h1.cookie;
            byte[] remote = Arrays.copyOfRange(h1.body, 0, 16);
            byte[] serverHash = Arrays.copyOfRange(h1.body, 16, 48);
            if (!Arrays.equals(serverHash, sha256(concat(local, remote, auth)))) {
                throw new IOException("Tapo login didn't match. Check this plug's email and password in tapo.json.");
            }
            Resp h2 = post("/app/handshake2", sha256(concat(remote, local, auth)), cookie);
            if (h2.code != 200) throw new IOException("handshake2 refused (HTTP " + h2.code + ")");

            key = Arrays.copyOf(sha256(concat("lsk".getBytes(StandardCharsets.US_ASCII), local, remote, auth)), 16);
            byte[] ivSeq = sha256(concat("iv".getBytes(StandardCharsets.US_ASCII), local, remote, auth));
            ivBase = Arrays.copyOf(ivSeq, 12);
            seq = ByteBuffer.wrap(ivSeq, 28, 4).getInt();
            sig = Arrays.copyOf(sha256(concat("ldk".getBytes(StandardCharsets.US_ASCII), local, remote, auth)), 28);
        }

        synchronized JSONObject call(String method) throws Exception {
            for (int attempt = 0; attempt < 2; attempt++) {
                seq++;
                byte[] seqBytes = ByteBuffer.allocate(4).putInt(seq).array();
                byte[] iv = concat(ivBase, seqBytes);
                String msg = new JSONObject().put("method", method).put("requestTimeMils", System.currentTimeMillis()).toString();
                byte[] cipher = aes(javax.crypto.Cipher.ENCRYPT_MODE, iv, msg.getBytes(StandardCharsets.UTF_8));
                byte[] payload = concat(sha256(concat(sig, seqBytes, cipher)), cipher);
                Resp r = post("/app/request?seq=" + seq, payload, cookie);
                if (r.code == 403 || r.code == 401) { handshake(); continue; } // session expired
                if (r.code != 200 || r.body.length <= 32) throw new IOException(method + " failed (HTTP " + r.code + ")");
                byte[] plain = aes(javax.crypto.Cipher.DECRYPT_MODE, iv, Arrays.copyOfRange(r.body, 32, r.body.length));
                JSONObject j = new JSONObject(new String(plain, StandardCharsets.UTF_8));
                if (j.optInt("error_code", 0) != 0) throw new IOException(method + " error " + j.optInt("error_code"));
                return j.optJSONObject("result") == null ? new JSONObject() : j.getJSONObject("result");
            }
            throw new IOException("couldn't keep a session with the plug");
        }

        private byte[] aes(int mode, byte[] iv, byte[] data) throws Exception {
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(mode, new javax.crypto.spec.SecretKeySpec(key, "AES"), new javax.crypto.spec.IvParameterSpec(iv));
            return c.doFinal(data);
        }

        private Resp post(String path, byte[] body, String cookie) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + path).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            if (cookie != null) c.setRequestProperty("Cookie", cookie);
            try (OutputStream o = c.getOutputStream()) {
                o.write(body);
            }
            Resp r = new Resp();
            r.code = c.getResponseCode();
            List<String> set = c.getHeaderFields().get("Set-Cookie");
            if (set != null) for (String sc : set) {
                if (sc.startsWith("TP_SESSIONID=")) r.cookie = sc.split(";", 2)[0];
            }
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) try (InputStream s = in) {
                r.body = Http.readAll(s);
            }
            c.disconnect();
            return r;
        }
    }

    private static final class Resp {
        int code;
        byte[] body = new byte[0];
        String cookie;
    }

    // ---- helpers ----

    private static byte[] sha256(byte[] b) throws Exception { return MessageDigest.getInstance("SHA-256").digest(b); }

    private static byte[] sha1(byte[] b) throws Exception { return MessageDigest.getInstance("SHA-1").digest(b); }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
