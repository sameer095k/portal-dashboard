package com.sameer.dashserver;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * Minimal Google API client for the Portal's service-account key.
 *
 * The key (sa-key.json in the app's private files) belongs to a robot
 * account that Sameer shares exactly two things with: the grocery
 * spreadsheet (editor) and the Family calendar (writer — read and
 * insert events; his primary calendar is deliberately NOT shared).
 * This client mints
 * OAuth tokens from it (JWT bearer flow) and makes the REST calls.
 * No key file -> every call throws KeyMissing and the API layer
 * answers 501, so the dashboard falls back to its snapshot.
 */
public class GoogleClient {
    static class KeyMissing extends Exception { }

    private final File keyFile;
    private String email;
    private PrivateKey privateKey;
    private String token;
    private long tokenExpMs;

    GoogleClient(Context ctx) {
        keyFile = new File(ctx.getFilesDir(), "sa-key.json");
    }

    boolean hasKey() { return keyFile.exists(); }

    private synchronized void loadKey() throws Exception {
        if (privateKey != null) return;
        String text;
        try (InputStream in = new FileInputStream(keyFile)) {
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            text = sb.toString();
        }
        JSONObject j = new JSONObject(text);
        email = j.getString("client_email");
        String pem = j.getString("private_key")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.decode(pem, Base64.DEFAULT);
        privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static String b64url(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private synchronized String accessToken() throws Exception {
        if (!hasKey()) throw new KeyMissing();
        loadKey();
        long now = System.currentTimeMillis();
        if (token != null && now < tokenExpMs - 60_000) return token;

        long iat = now / 1000, exp = iat + 3600;
        // calendar.readonly covers calendarList (the events scope
        // alone gets 403 there — verified live 2026-10-25);
        // calendar.events adds event insert into the Family calendar.
        String scope = "https://www.googleapis.com/auth/spreadsheets"
                + " https://www.googleapis.com/auth/calendar.readonly"
                + " https://www.googleapis.com/auth/calendar.events";
        String header = b64url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String claims = b64url(("{\"iss\":\"" + email + "\",\"scope\":\"" + scope
                + "\",\"aud\":\"https://oauth2.googleapis.com/token\",\"iat\":" + iat
                + ",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update((header + "." + claims).getBytes(StandardCharsets.UTF_8));
        String jwt = header + "." + claims + "." + b64url(sig.sign());

        String body = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"
                + "&assertion=" + jwt;
        String resp = http("POST", "https://oauth2.googleapis.com/token", null,
                body.getBytes(StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded");
        JSONObject j = new JSONObject(resp);
        token = j.getString("access_token");
        tokenExpMs = now + j.optLong("expires_in", 3600) * 1000;
        return token;
    }

    String get(String url) throws Exception {
        return http("GET", url, accessToken(), null, null);
    }

    String postJson(String url, String json) throws Exception {
        return http("POST", url, accessToken(),
                json.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    String putJson(String url, String json) throws Exception {
        return http("PUT", url, accessToken(),
                json.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    /** Partial update. HttpURLConnection's method set has no PATCH,
        so this rides a POST with Google's X-HTTP-Method-Override
        header — the Calendar API honors it as a true PATCH. */
    String patchJson(String url, String json) throws Exception {
        return http("POST", url, accessToken(),
                json.getBytes(StandardCharsets.UTF_8), "application/json",
                "PATCH");
    }

    String delete(String url) throws Exception {
        return http("DELETE", url, accessToken(), null, null);
    }

    private String http(String method, String url, String bearer, byte[] body,
                        String contentType) throws Exception {
        return http(method, url, bearer, body, contentType, null);
    }

    private String http(String method, String url, String bearer, byte[] body,
                        String contentType, String methodOverride) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        if (methodOverride != null) {
            c.setRequestProperty("X-HTTP-Method-Override", methodOverride);
        }
        c.setConnectTimeout(15_000);
        c.setReadTimeout(20_000);
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (body != null) {
            c.setDoOutput(true);
            if (contentType != null) c.setRequestProperty("Content-Type", contentType);
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        if (code < 200 || code >= 300) {
            if (code == 401) { synchronized (this) { token = null; } }
            throw new Exception("HTTP " + code + ": " + sb.toString().substring(
                    0, Math.min(300, sb.length())));
        }
        return sb.toString();
    }
}
