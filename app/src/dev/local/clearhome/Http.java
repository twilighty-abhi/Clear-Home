package dev.local.clearhome;

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

/** Tiny blocking HTTP helpers for the Google integrations (call off the UI thread). */
final class Http {
    private Http() {}

    static final class Resp {
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
            if (e instanceof JSONObject) {
                JSONObject o = (JSONObject) e;
                return (o.optString("status", "") + " " + o.optString("message", "")).trim();
            }
            if (e instanceof String) return (e + " " + j.optString("error_description", "")).trim();
            return "HTTP " + code;
        }
    }

    static Resp postForm(String url, String... kv) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(kv[i])).append('=').append(enc(kv[i + 1]));
        }
        return request("POST", url, "application/x-www-form-urlencoded",
                sb.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    static Resp request(String method, String url, String contentType, byte[] body, String bearer) throws IOException {
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

    /** Downloads to dest atomically (via a .part file). */
    static void download(String url, String bearer, File dest) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (c.getResponseCode() != 200) {
            int code = c.getResponseCode();
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        File tmp = new File(dest.getPath() + ".part");
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
        if (!tmp.renameTo(dest)) throw new IOException("couldn't save " + dest.getName());
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    /** "3.5s" -> 3500 ms, clamped to at least minMs. */
    static long durationMs(String s, long fallbackMs, long minMs) {
        try {
            if (s != null && s.endsWith("s")) {
                return Math.max(minMs, (long) (Double.parseDouble(s.substring(0, s.length() - 1)) * 1000));
            }
        } catch (NumberFormatException ignored) {
        }
        return fallbackMs;
    }
}
