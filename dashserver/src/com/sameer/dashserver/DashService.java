package com.sameer.dashserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tiny static file server: serves /sdcard/portal-dashboard on port 8321
 * (localhost + LAN/tailnet interfaces) so Immortal's web screensaver
 * (http/https only) can load the dashboard fully locally. Also accepts a
 * token-guarded PUT push for the data snapshot (data.json + word audio).
 */
public class DashService extends Service {
    static final int PORT = 8321;
    static final File ROOT = new File("/sdcard/portal-dashboard");
    // Shared secret for the snapshot push (PUT /upload/...). Baked into this
    // personal build; the matching copy lives in Muse's workspace only.
    static final String TOKEN = "4770f3febc9f5ed346c173de8598f701";
    // Only these files may be overwritten by a push.
    static final String[] UPLOADABLE = {
        "/data.json", "/audio/wotd-en.mp3", "/audio/wotd-ur.mp3", "/index.html"
    };

    private volatile boolean running = false;
    private ServerSocket server;
    private PowerManager.WakeLock wakeLock;
    private PortalApi api;
    private OnDevice onDevice;
    /** Photo uploads are accepted only while this window is open
        (the dashboard's Add Photos button opens it for 10 minutes). */
    private volatile long uploadOpenUntilMs = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        api = new PortalApi(new GoogleClient(this));
        onDevice = new OnDevice(this);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                "dashserver", "Dashboard server", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "dashserver")
                .setContentTitle("Dashboard server")
                .setContentText("Serving http://127.0.0.1:" + PORT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        startForeground(1, n);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DashServer:serve");
        wakeLock.acquire();

        running = true;
        new Thread(this::serveLoop, "dashserver").start();
    }

    private void serveLoop() {
        try {
            server = new ServerSocket(PORT, 50); // all interfaces: localhost + tailnet
            while (running) {
                final Socket s = server.accept();
                new Thread(() -> handle(s), "dashserver-conn").start();
            }
        } catch (IOException e) {
            // Port busy or socket closed at shutdown; service stays foreground.
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(10000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) { s.close(); return; }
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(
                        line.substring(0, colon).trim().toLowerCase(),
                        line.substring(colon + 1).trim());
            }

            String[] parts = requestLine.split(" ");
            String method = parts.length >= 1 ? parts[0] : "GET";
            String target = parts.length >= 2 ? parts[1] : "/";
            String path = target;
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            path = URLDecoder.decode(path, "UTF-8");
            if (path.equals("/")) path = "/index.html";

            if (path.equals("/photo-add")) { handlePhotoAdd(s, in, headers, method); return; }
            if (path.startsWith("/api/")) { handleApi(s, in, headers, method, target); return; }
            if (path.startsWith("/thumb/") && method.equals("GET")) {
                handleThumb(s, path.substring("/thumb/".length()));
                return;
            }
            if (method.equals("PUT")) { handlePut(s, in, headers, path); return; }
            if (method.equals("GET") && path.equals("/audio/wotd-ur.mp3")
                    && onDevice != null) {
                // A request for the word clip may be the first sign
                // today's clip needs synthesizing on-device.
                onDevice.kickAudioIfStale();
            }
            if (!method.equals("GET") && !method.equals("HEAD")) {
                OutputStream out = s.getOutputStream();
                byte[] body = "method not allowed".getBytes("UTF-8");
                writeHead(out, 405, "Method Not Allowed", "text/plain", body.length);
                out.write(body); out.flush(); s.close(); return;
            }

            String rootCanon = ROOT.getCanonicalPath();
            File f = new File(ROOT, path);
            String canon = f.getCanonicalPath();
            OutputStream out = s.getOutputStream();
            if (!canon.startsWith(rootCanon) || !f.isFile()) {
                byte[] body = "not found".getBytes("UTF-8");
                writeHead(out, 404, "Not Found", "text/plain", body.length);
                out.write(body);
            } else {
                boolean photo = path.startsWith("/photos/") && isImageName(f.getName());
                if (photo) {
                    writeHeadCache(out, 200, "OK", mimeFor(f.getName()), f.length(),
                            "public, max-age=31536000, immutable");
                } else {
                    writeHead(out, 200, "OK", mimeFor(f.getName()), f.length());
                }
                FileInputStream fis = new FileInputStream(f);
                byte[] buf = new byte[32768];
                int n;
                while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
                fis.close();
            }
            out.flush();
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    /** Live Google endpoints (grocery + agenda) via the service-account key. */
    private void handleApi(Socket s, InputStream in, Map<String, String> headers,
                           String method, String target) throws Exception {
        String body = "";
        String lenH = headers.get("content-length");
        if (lenH != null) {
            int len = Integer.parseInt(lenH.trim());
            if (len > 0 && len < 100_000) {
                byte[] buf = new byte[len];
                int read = 0;
                while (read < len) {
                    int n = in.read(buf, read, len - read);
                    if (n < 0) break;
                    read += n;
                }
                body = new String(buf, 0, read, "UTF-8");
            }
        }
        String path = target, query = "";
        int q = target.indexOf('?');
        if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
        path = URLDecoder.decode(path, "UTF-8");
        // Queued calendar adds retry on-device: a calendar read
        // first tries to flush the queue through the same direct
        // Family-calendar insert the add path uses (this replaces
        // the old host-side drain in build_data.py). Best effort —
        // failures stay queued and keep their "Queued" marking.
        if (method.equals("GET") && path.equals("/api/calendar")) flushQueue();
        PortalApi.Result r = null;
        // POST /api/show is the one local API that changes what the
        // kitchen screen shows, so it takes the upload bearer token —
        // the page only ever reads and clears (see handleLocalApi).
        // Exception: a peer on loopback is an app on this device itself
        // (portal-muse's display.show gadget command posts the card
        // Muse asked for) — already inside the device's trust
        // boundary, so it needs no token. Anything arriving over the
        // network still needs the token, exactly as before.
        if (method.equals("POST") && path.equals("/api/show")) {
            String auth = headers.get("authorization");
            java.net.InetAddress peer = s.getInetAddress();
            boolean onDevice = peer != null && peer.isLoopbackAddress();
            if (!onDevice && (auth == null || !auth.equals("Bearer " + TOKEN))) {
                r = new PortalApi.Result(401, "{\"error\":\"auth\"}");
            } else {
                r = saveShowCard(body);
            }
        }
        if (r == null) r = handleLocalApi(method, path, body);
        if (r == null) r = api.handle(method, path, query, body);
        OutputStream out = s.getOutputStream();
        byte[] bytes = r.body.getBytes("UTF-8");
        String reason = r.status == 200 ? "OK" : r.status == 501 ? "Not Implemented"
                : r.status == 404 ? "Not Found" : r.status == 405 ? "Method Not Allowed"
                : r.status == 401 ? "Unauthorized" : r.status == 400 ? "Bad Request"
                : "Bad Gateway";
        writeHead(out, r.status, reason, "application/json", bytes.length);
        out.write(bytes); out.flush(); s.close();
    }

    /** Dashboard-local endpoints (no Google key needed): the rail's
        persisted view, the photo library + album ops, the calendar
        add (native Family-calendar insert via PortalApi, with the
        on-device queue as fallback), the upload window, and the
        on-device content fetches (prayer times, word of the day —
        see OnDevice). Returns null for paths that belong to
        PortalApi. */
    private PortalApi.Result handleLocalApi(String method, String path, String body) {
        try {
            switch (path) {
                case "/api/ui-state": {
                    File f = new File(ROOT, "ui-state.json");
                    if (method.equals("GET")) {
                        String json = "{\"view\":\"home\"}";
                        if (f.isFile()) {
                            try (InputStream in = new FileInputStream(f)) {
                                ByteArrayOutputStream b = new ByteArrayOutputStream();
                                byte[] buf = new byte[1024]; int n;
                                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                                String t = b.toString("UTF-8").trim();
                                if (t.startsWith("{")) json = t;
                            }
                        }
                        return new PortalApi.Result(200, json);
                    }
                    if (method.equals("POST")) {
                        org.json.JSONObject req = new org.json.JSONObject(body);
                        org.json.JSONObject cur = new org.json.JSONObject();
                        if (f.isFile()) {
                            try (InputStream in = new FileInputStream(f)) {
                                ByteArrayOutputStream b = new ByteArrayOutputStream();
                                byte[] buf = new byte[1024]; int n;
                                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                                String t = b.toString("UTF-8").trim();
                                if (t.startsWith("{")) cur = new org.json.JSONObject(t);
                            } catch (Exception ignored) { }
                        }
                        if (req.has("view")) {
                            String view = req.optString("view", "home");
                            switch (view) {
                                case "home": case "prayer": case "weather":
                                case "groceries": case "photos": case "calendar":
                                case "timer":
                                    break;
                                default: view = "home";
                            }
                            cur.put("view", view);
                        }
                        if (req.has("rail")) cur.put("rail", req.getBoolean("rail"));
                        if (req.has("album")) {
                            String al = req.optString("album", "All").trim();
                            if (al.isEmpty() || al.length() > 40
                                    || al.contains("/") || al.contains("\\")) al = "All";
                            cur.put("album", al);
                        }
                        /* Photo settings live server-side so the app and
                           screensaver faces (separate WebView storage)
                           can never disagree on rotate speed or pin. */
                        if (req.has("rotate")) {
                            int ms = req.optInt("rotate", 30000);
                            if (ms != 0 && ms != 10000 && ms != 30000
                                    && ms != 60000 && ms != 300000) ms = 30000;
                            cur.put("rotate", ms);
                        }
                        if (req.has("pin")) {
                            if (req.isNull("pin")) {
                                cur.put("pin", org.json.JSONObject.NULL);
                            } else {
                                String p = req.optString("pin", "").trim();
                                if (!p.isEmpty() && p.length() <= 200
                                        && !p.contains("..")) cur.put("pin", p);
                            }
                        }
                        if (req.has("order")) {
                            String od = req.optString("order", "shuffle");
                            if (!od.equals("seq") && !od.equals("shuffle")) od = "shuffle";
                            cur.put("order", od);
                        }
                        if (!cur.has("view")) cur.put("view", "home");
                        if (!ROOT.exists()) ROOT.mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(f)) {
                            fos.write(cur.toString().getBytes("UTF-8"));
                        }
                        return new PortalApi.Result(200, "{\"ok\":true}");
                    }
                    return new PortalApi.Result(405, "{\"error\":\"method\"}");
                }
                case "/api/photos": {
                    if (!method.equals("GET")) return new PortalApi.Result(405, "{\"error\":\"method\"}");
                    File dir = new File(ROOT, "photos");
                    org.json.JSONArray albums = new org.json.JSONArray();
                    org.json.JSONArray all = new org.json.JSONArray();
                    org.json.JSONArray main = imageList(dir, "photos/");
                    for (int i = 0; i < main.length(); i++) all.put(main.get(i));
                    albums.put(new org.json.JSONObject().put("name", "Main").put("photos", main));
                    List<String> subs = new ArrayList<>();
                    File[] entries = dir.listFiles();
                    if (entries != null) {
                        for (File f : entries) {
                            if (f.isDirectory() && !f.getName().startsWith(".")) {
                                subs.add(f.getName());
                            }
                        }
                    }
                    Collections.sort(subs);
                    for (String sub : subs) {
                        org.json.JSONArray ps = imageList(new File(dir, sub), "photos/" + sub + "/");
                        for (int i = 0; i < ps.length(); i++) all.put(ps.get(i));
                        albums.put(new org.json.JSONObject().put("name", sub).put("photos", ps));
                    }
                    return new PortalApi.Result(200, new org.json.JSONObject()
                            .put("albums", albums).put("photos", all).toString());
                }
                case "/api/photos/delete": {
                    if (!method.equals("POST")) return new PortalApi.Result(405, "{\"error\":\"method\"}");
                    org.json.JSONObject req = new org.json.JSONObject(body);
                    File f = photoFile(req.getString("path"));
                    if (!f.isFile()) return new PortalApi.Result(404, "{\"error\":\"no file\"}");
                    if (!f.delete()) throw new Exception("delete failed");
                    return new PortalApi.Result(200, "{\"ok\":true}");
                }
                case "/api/photos/move": {
                    if (!method.equals("POST")) return new PortalApi.Result(405, "{\"error\":\"method\"}");
                    org.json.JSONObject req = new org.json.JSONObject(body);
                    File src = photoFile(req.getString("path"));
                    if (!src.isFile()) return new PortalApi.Result(404, "{\"error\":\"no file\"}");
                    String album = req.optString("album", "Main").trim();
                    File photosRoot = new File(ROOT, "photos");
                    File destDir;
                    if (album.isEmpty() || album.equalsIgnoreCase("Main")) {
                        destDir = photosRoot;
                    } else {
                        File cand = new File(photosRoot, album);
                        if (!cand.isDirectory()) {
                            String clean = cleanAlbum(album);
                            if (clean == null) return new PortalApi.Result(400, "{\"error\":\"bad album\"}");
                            cand = new File(photosRoot, clean);
                        }
                        destDir = cand;
                    }
                    if (!destDir.exists()) destDir.mkdirs();
                    File dest = new File(destDir, src.getName());
                    if (dest.getCanonicalFile().equals(src.getCanonicalFile())) {
                        return new PortalApi.Result(200, "{\"ok\":true}");
                    }
                    int k = 1;
                    while (dest.exists()) {
                        String nm = src.getName();
                        int dot = nm.lastIndexOf('.');
                        String base = dot > 0 ? nm.substring(0, dot) : nm;
                        String ext = dot > 0 ? nm.substring(dot) : "";
                        dest = new File(destDir, base + "-" + (k++) + ext);
                    }
                    if (!src.renameTo(dest)) throw new Exception("move failed");
                    return new PortalApi.Result(200, "{\"ok\":true}");
                }
                case "/api/photos/copy": {
                    // Multi-album: a photo may live in several album
                    // folders at once — copy leaves the source in place
                    // (unlike move). Delete stays per-copy.
                    if (!method.equals("POST")) return new PortalApi.Result(405, "{\"error\":\"method\"}");
                    org.json.JSONObject req = new org.json.JSONObject(body);
                    File src = photoFile(req.getString("path"));
                    if (!src.isFile()) return new PortalApi.Result(404, "{\"error\":\"no file\"}");
                    String album = req.optString("album", "Main").trim();
                    File photosRoot = new File(ROOT, "photos");
                    File destDir;
                    if (album.isEmpty() || album.equalsIgnoreCase("Main")) {
                        destDir = photosRoot;
                    } else {
                        File cand = new File(photosRoot, album);
                        if (!cand.isDirectory()) {
                            String clean = cleanAlbum(album);
                            if (clean == null) return new PortalApi.Result(400, "{\"error\":\"bad album\"}");
                            cand = new File(photosRoot, clean);
                        }
                        destDir = cand;
                    }
                    if (!destDir.exists()) destDir.mkdirs();
                    File dest = new File(destDir, src.getName());
                    if (dest.getCanonicalFile().equals(src.getCanonicalFile())) {
                        return new PortalApi.Result(200, "{\"ok\":true}");
                    }
                    int k = 1;
                    while (dest.exists()) {
                        String nm = src.getName();
                        int dot = nm.lastIndexOf('.');
                        String base = dot > 0 ? nm.substring(0, dot) : nm;
                        String ext = dot > 0 ? nm.substring(dot) : "";
                        dest = new File(destDir, base + "-" + (k++) + ext);
                    }
                    File tmp = new File(destDir, dest.getName() + ".tmp");
                    try (FileInputStream fis = new FileInputStream(src);
                         FileOutputStream fos = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[32768];
                        int n;
                        while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                    if (!tmp.renameTo(dest)) { tmp.delete(); throw new Exception("copy failed"); }
                    return new PortalApi.Result(200, "{\"ok\":true}");
                }
                case "/api/photos/unfile": {
                    // Remove from album (his ask 2026-10-31): the photo
                    // leaves its album folder but survives in Main. If
                    // Main already holds the same image (same bytes),
                    // this album copy is deleted; otherwise the file
                    // moves to Main. Already in Main: no-op.
                    if (!method.equals("POST")) return new PortalApi.Result(405, "{\"error\":\"method\"}");
                    org.json.JSONObject req = new org.json.JSONObject(body);
                    File src = photoFile(req.getString("path"));
                    if (!src.isFile()) return new PortalApi.Result(404, "{\"error\":\"no file\"}");
                    File photosRoot = new File(ROOT, "photos").getCanonicalFile();
                    if (photosRoot.equals(src.getParentFile())) {
                        return new PortalApi.Result(200, "{\"ok\":true,\"action\":\"none\"}");
                    }
                    String srcHash = md5Hex(src);
                    boolean twin = false;
                    File[] rootFiles = photosRoot.listFiles();
                    if (rootFiles != null) {
                        for (File f : rootFiles) {
                            if (f.isFile() && f.length() == src.length()
                                    && md5Hex(f).equals(srcHash)) { twin = true; break; }
                        }
                    }
                    if (twin) {
                        if (!src.delete()) throw new Exception("delete failed");
                        return new PortalApi.Result(200, "{\"ok\":true,\"action\":\"copy-deleted\"}");
                    }
                    File dest = new File(photosRoot, src.getName());
                    int k = 1;
                    while (dest.exists()) {
                        String nm = src.getName();
                        int dot = nm.lastIndexOf('.');
                        String base = dot > 0 ? nm.substring(0, dot) : nm;
                        String ext = dot > 0 ? nm.substring(dot) : "";
                        dest = new File(photosRoot, base + "-" + (k++) + ext);
                    }
                    if (!src.renameTo(dest)) throw new Exception("move failed");
                    return new PortalApi.Result(200, "{\"ok\":true,\"action\":\"moved\"}");
                }
                case "/api/photos/album": {
                    if (!method.equals("POST")) return new PortalApi.Result(405, "{\"error\":\"method\"}");
                    org.json.JSONObject req = new org.json.JSONObject(body);
                    String clean = cleanAlbum(req.optString("name", ""));
                    if (clean == null) return new PortalApi.Result(400, "{\"error\":\"bad name\"}");
                    File d = new File(new File(ROOT, "photos"), clean);
                    if (!d.exists()) d.mkdirs();
                    return new PortalApi.Result(200, "{\"ok\":true}");
                }
                case "/api/photos/album-delete": {
                    if (!method.equals("POST")) return new PortalApi.Result(405, "{\"error\":\"method\"}");
                    org.json.JSONObject req = new org.json.JSONObject(body);
                    String name = req.optString("name", "").trim();
                    File d = new File(new File(ROOT, "photos"), name);
                    File root = new File(ROOT, "photos").getCanonicalFile();
                    if (!d.getCanonicalFile().getPath().startsWith(
                            root.getPath() + File.separator)) {
                        return new PortalApi.Result(400, "{\"error\":\"bad album\"}");
                    }
                    if (d.isDirectory()) {
                        File[] kids = d.listFiles();
                        if (kids != null && kids.length > 0) {
                            return new PortalApi.Result(409, "{\"error\":\"not empty\"}");
                        }
                        d.delete();
                    }
                    return new PortalApi.Result(200, "{\"ok\":true}");
                }
                case "/api/photos/upload-open": {
                    if (!method.equals("POST")) return new PortalApi.Result(405, "{\"error\":\"method\"}");
                    uploadOpenUntilMs = System.currentTimeMillis() + 10 * 60_000L;
                    String ip = lanIp();
                    String url = "http://" + (ip != null ? ip : "127.0.0.1") + ":" + PORT + "/photo-add";
                    return new PortalApi.Result(200, new org.json.JSONObject()
                            .put("url", url).put("minutes", 10).toString());
                }
                case "/api/calendar/add": {
                    // Native add: insert straight into the Family
                    // calendar (the one calendar the service account
                    // may write) via PortalApi. If the insert fails
                    // for any reason, fall back to the on-device queue
                    // (cal-queue.json, served statically so the page
                    // can merge pending events into the week view),
                    // which the calendar read retries on-device (see
                    // flushQueue) — so no add is ever lost. Server
                    // logs say which path each add took; the page
                    // contract is unchanged.
                    if (!method.equals("POST")) return new PortalApi.Result(405, "{\"error\":\"method\"}");
                    org.json.JSONObject req = new org.json.JSONObject(body);
                    String title = req.optString("title", "").trim();
                    String start = req.optString("start", "").trim();
                    String end = req.optString("end", "").trim();
                    if (title.isEmpty() || start.isEmpty() || end.isEmpty()) {
                        return new PortalApi.Result(400, "{\"error\":\"bad event\"}");
                    }
                    try {
                        org.json.JSONObject created = api.insertFamilyEvent(req);
                        String gid = created.optString("id", "");
                        android.util.Log.i("DashServer", "calendar add: DIRECT"
                                + " insert into Family calendar ok, google id=" + gid);
                        return new PortalApi.Result(200, new org.json.JSONObject()
                                .put("ok", true).put("id", gid).toString());
                    } catch (Exception e) {
                        android.util.Log.w("DashServer", "calendar add: direct"
                                + " insert FAILED (" + e.getMessage()
                                + ") — falling back to on-device queue");
                    }
                    org.json.JSONObject entry = new org.json.JSONObject()
                            .put("id", java.util.UUID.randomUUID().toString())
                            .put("title", title)
                            .put("start", start)
                            .put("end", end)
                            .put("allday", req.optBoolean("allday", false));
                    if (req.has("location")) entry.put("location", req.optString("location", ""));
                    synchronized (QUEUE_LOCK) {
                        org.json.JSONArray q = readQueue();
                        q.put(entry);
                        writeQueue(q);
                    }
                    return new PortalApi.Result(200, new org.json.JSONObject()
                            .put("ok", true).put("id", entry.getString("id")).toString());
                }
                case "/api/calendar/dequeue": {
                    if (!method.equals("POST")) return new PortalApi.Result(405, "{\"error\":\"method\"}");
                    org.json.JSONObject req = new org.json.JSONObject(body);
                    org.json.JSONArray ids = req.optJSONArray("ids");
                    java.util.Set<String> drop = new java.util.HashSet<>();
                    if (ids != null) {
                        for (int i = 0; i < ids.length(); i++) drop.add(ids.getString(i));
                    }
                    int removed = 0;
                    synchronized (QUEUE_LOCK) {
                        org.json.JSONArray q = readQueue();
                        org.json.JSONArray keep = new org.json.JSONArray();
                        for (int i = 0; i < q.length(); i++) {
                            org.json.JSONObject e = q.getJSONObject(i);
                            if (drop.contains(e.optString("id", ""))) removed++;
                            else keep.put(e);
                        }
                        if (removed > 0) writeQueue(keep);
                    }
                    return new PortalApi.Result(200, new org.json.JSONObject()
                            .put("ok", true).put("removed", removed).toString());
                }
                case "/api/show": {
                    if (!method.equals("GET")) return new PortalApi.Result(405, "{\"error\":\"method\"}");
                    synchronized (SHOW_LOCK) {
                        File f = new File(ROOT, "show.json");
                        if (f.isFile()) {
                            try (InputStream in = new FileInputStream(f)) {
                                ByteArrayOutputStream b = new ByteArrayOutputStream();
                                byte[] buf = new byte[1024]; int n;
                                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                                String t = b.toString("UTF-8").trim();
                                if (t.startsWith("{")) return new PortalApi.Result(200, t);
                            }
                        }
                    }
                    return new PortalApi.Result(200, "{\"id\":null}");
                }
                case "/api/show/clear": {
                    if (!method.equals("POST")) return new PortalApi.Result(405, "{\"error\":\"method\"}");
                    synchronized (SHOW_LOCK) {
                        File f = new File(ROOT, "show.json");
                        if (f.exists()) f.delete();
                    }
                    return new PortalApi.Result(200, "{\"ok\":true}");
                }
                case "/api/timer/chime": {
                    if (!method.equals("POST")) return new PortalApi.Result(405, "{\"error\":\"method\"}");
                    ChimePlayer.play();
                    return new PortalApi.Result(200, "{\"ok\":true}");
                }
                case "/api/prayer": {
                    if (!method.equals("GET")) return new PortalApi.Result(405, "{\"error\":\"method\"}");
                    return new PortalApi.Result(200, onDevice.prayer().toString());
                }
                case "/api/word": {
                    if (!method.equals("GET")) return new PortalApi.Result(405, "{\"error\":\"method\"}");
                    return new PortalApi.Result(200, onDevice.word().toString());
                }
                default:
                    return null;
            }
        } catch (Exception e) {
            return new PortalApi.Result(502, "{\"error\":\"local api\"}");
        }
    }

    private static final Object SHOW_LOCK = new Object();

    /** Timer-done chime (6.14): a synthesized two-tone bell played
        natively on the alarm stream, so the dashboard page can sound
        the timer without depending on WebView media policies. The
        page POSTs /api/timer/chime once per repeat cycle while its
        done state stands; each POST restarts the chime. */
    static final class ChimePlayer {
        private static final int SR = 44100;
        private static short[] buffer;
        private static AudioTrack track;

        private static synchronized short[] buffer() {
            if (buffer != null) return buffer;
            double[] tones = {1046.5, 1318.5, 1568.0};   // C6 E6 G6
            double[] durs = {0.45, 0.45, 1.1};
            int total = 0;
            for (double d : durs) total += (int) (SR * d);
            short[] out = new short[total];
            int pos = 0;
            for (int t = 0; t < tones.length; t++) {
                int n = (int) (SR * durs[t]);
                for (int i = 0; i < n; i++) {
                    double time = i / (double) SR;
                    double env = Math.min(1.0, i / (SR * 0.01))
                            * Math.exp(-2.2 * time / durs[t]);
                    double v = Math.sin(2 * Math.PI * tones[t] * time)
                            + 0.35 * Math.sin(2 * Math.PI * tones[t] * 2 * time);
                    out[pos++] = (short) (v * env * 0.55 * Short.MAX_VALUE);
                }
            }
            buffer = out;
            return out;
        }

        static synchronized void play() {
            try {
                short[] buf = buffer();
                if (track != null) {
                    try { track.stop(); } catch (Exception ignored) { }
                    track.release();
                    track = null;
                }
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SR)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(buf.length * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();
                track.write(buf, 0, buf.length);
                track.play();
            } catch (Exception ignored) { }
        }
    }

    /** Store a Muse-pushed show card (show.json in the served dir;
        the page polls GET /api/show and renders it until the card is
        dismissed with ✕ or replaced — Open only launches the link
        and leaves the card in place, so a swallowed launch can never
        lose it). POSTed only with the upload bearer token — see
        handleApi. Each POST replaces the current card and re-stamps
        id/created with the server clock. v2: the url is optional
        when the card carries sections (ingredients/steps on the
        card itself). */
    private PortalApi.Result saveShowCard(String body) {
        try {
            org.json.JSONObject req = new org.json.JSONObject(body);
            String title = req.optString("title", "").trim();
            String url = req.optString("url", "").trim();
            boolean urlOk = url.isEmpty() || (url.length() <= 1000
                    && (url.startsWith("http://") || url.startsWith("https://")));
            org.json.JSONArray sections = sanitizeSections(req.optJSONArray("sections"));
            int timerSec = req.optInt("timer_seconds", 0);
            if (timerSec < 0 || timerSec > 86400) timerSec = 0;
            if (title.isEmpty() || title.length() > 200 || !urlOk
                    || (url.isEmpty() && sections == null && timerSec <= 0)) {
                return new PortalApi.Result(400, "{\"error\":\"bad card\"}");
            }
            long now = System.currentTimeMillis();
            org.json.JSONObject card = new org.json.JSONObject();
            card.put("id", now);
            card.put("created", now);
            card.put("title", title);
            if (!url.isEmpty()) card.put("url", url);
            if (sections != null) card.put("sections", sections);
            String sub = req.optString("subtitle", "").trim();
            if (!sub.isEmpty()) card.put("subtitle",
                    sub.length() > 300 ? sub.substring(0, 300) : sub);
            String img = req.optString("image", "").trim();
            if (!img.isEmpty() && img.length() <= 1000
                    && (img.startsWith("http://") || img.startsWith("https://"))) {
                card.put("image", img);
            }
            if (timerSec > 0) card.put("timer_seconds", timerSec);
            synchronized (SHOW_LOCK) {
                if (!ROOT.exists()) ROOT.mkdirs();
                try (FileOutputStream fos = new FileOutputStream(new File(ROOT, "show.json"))) {
                    fos.write(card.toString().getBytes("UTF-8"));
                }
            }
            return new PortalApi.Result(200, new org.json.JSONObject()
                    .put("ok", true).put("id", now).toString());
        } catch (Exception e) {
            return new PortalApi.Result(400, "{\"error\":\"bad card\"}");
        }
    }

    /** v2 rich cards: validate a [{heading, lines}] array — at most
        6 sections, 40 lines per section, heading ≤120 chars, line
        ≤300 chars (long enough for a full recipe). Returns a
        sanitized copy, or null when absent, empty, or invalid. */
    private static org.json.JSONArray sanitizeSections(org.json.JSONArray in) {
        if (in == null || in.length() == 0 || in.length() > 6) return null;
        try {
            org.json.JSONArray out = new org.json.JSONArray();
            for (int i = 0; i < in.length(); i++) {
                org.json.JSONObject s = in.optJSONObject(i);
                if (s == null) return null;
                String heading = s.optString("heading", "").trim();
                if (heading.length() > 120) heading = heading.substring(0, 120);
                org.json.JSONArray linesIn = s.optJSONArray("lines");
                if (linesIn == null || linesIn.length() == 0 || linesIn.length() > 40) return null;
                org.json.JSONArray lines = new org.json.JSONArray();
                for (int j = 0; j < linesIn.length(); j++) {
                    String line = linesIn.optString(j, "").trim();
                    if (line.isEmpty()) continue;
                    if (line.length() > 300) line = line.substring(0, 300);
                    lines.put(line);
                }
                if (lines.length() == 0) return null;
                org.json.JSONObject sec = new org.json.JSONObject();
                sec.put("heading", heading);
                sec.put("lines", lines);
                out.put(sec);
            }
            return out.length() > 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static final Object QUEUE_LOCK = new Object();

    /** Retry queued calendar adds through the direct Family-calendar
        insert (the path /api/calendar/add tries first). Runs before
        a GET /api/calendar read is answered, so a queued event that
        lands appears in that same read as a normal Family event.
        Entries missing title/start/end are left queued untouched;
        entries whose insert fails stay queued too. Two-phase: the
        queue is snapshotted under the lock, inserts happen outside
        it, and only the succeeded ids are removed. */
    private void flushQueue() {
        org.json.JSONArray snapshot;
        synchronized (QUEUE_LOCK) { snapshot = readQueue(); }
        if (snapshot.length() == 0) return;
        java.util.Set<String> done = new java.util.HashSet<>();
        for (int i = 0; i < snapshot.length(); i++) {
            org.json.JSONObject e = snapshot.optJSONObject(i);
            if (e == null) continue;
            String id = e.optString("id", "");
            String title = e.optString("title", "").trim();
            String start = e.optString("start", "").trim();
            String end = e.optString("end", "").trim();
            if (id.isEmpty() || title.isEmpty() || start.isEmpty() || end.isEmpty()) continue;
            try {
                api.insertFamilyEvent(e);
                done.add(id);
                android.util.Log.i("DashServer", "calendar queue flush:"
                        + " inserted queued event " + id + " (" + title + ")");
            } catch (Exception ex) {
                android.util.Log.w("DashServer", "calendar queue flush:"
                        + " insert failed for " + id + " (" + title + "): "
                        + ex.getMessage() + " — stays queued");
            }
        }
        if (done.isEmpty()) return;
        synchronized (QUEUE_LOCK) {
            org.json.JSONArray q = readQueue();
            org.json.JSONArray keep = new org.json.JSONArray();
            for (int i = 0; i < q.length(); i++) {
                org.json.JSONObject e = q.optJSONObject(i);
                if (e == null) continue;
                if (done.contains(e.optString("id", ""))) continue;
                keep.put(e);
            }
            try { writeQueue(keep); } catch (Exception ignored) { }
        }
    }

    private static File queueFile() { return new File(ROOT, "cal-queue.json"); }

    private static org.json.JSONArray readQueue() {
        File f = queueFile();
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[4096]; int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                String t = b.toString("UTF-8").trim();
                if (t.startsWith("[")) return new org.json.JSONArray(t);
            } catch (Exception ignored) { }
        }
        return new org.json.JSONArray();
    }

    private static void writeQueue(org.json.JSONArray q) throws IOException {
        if (!ROOT.exists()) ROOT.mkdirs();
        File target = queueFile();
        File tmp = new File(ROOT, "cal-queue.json.tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(q.toString().getBytes("UTF-8"));
        }
        if (!tmp.renameTo(target)) {
            try (FileOutputStream fos = new FileOutputStream(target)) {
                fos.write(q.toString().getBytes("UTF-8"));
            }
            tmp.delete();
        }
    }

    private static boolean isImageName(String n) {
        n = n.toLowerCase(Locale.US);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".webp") || n.endsWith(".gif");
    }

    private static org.json.JSONArray imageList(File dir, String prefix) throws Exception {
        List<File> fs = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && isImageName(f.getName())) fs.add(f);
            }
        }
        Collections.sort(fs, (a, b) -> a.getName().compareTo(b.getName()));
        org.json.JSONArray arr = new org.json.JSONArray();
        // ?v=<mtime> versions the URL so the immutable cache headers on
        // photo responses can never serve a stale image after a replace.
        for (File f : fs) arr.put(prefix + f.getName() + "?v=" + (f.lastModified() / 1000));
        return arr;
    }

    /** Resolve a client-supplied photo path, refusing anything that
        escapes the photos folder. */
    private File photoFile(String path) throws Exception {
        File root = new File(ROOT, "photos").getCanonicalFile();
        File f = new File(ROOT, path).getCanonicalFile();
        if (!f.getPath().startsWith(root.getPath() + File.separator)) {
            throw new Exception("bad path");
        }
        return f;
    }

    private static String md5Hex(File f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = fis.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Album names: letters/digits/spaces and a few safe marks.
        "Main" and "All" are reserved (root bucket / union view). */
    private static String cleanAlbum(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty() || s.length() > 40) return null;
        if (s.equalsIgnoreCase("Main") || s.equalsIgnoreCase("All")) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == ' ' || c == '-'
                    || c == '&' || c == '\'' || c == '_')) return null;
        }
        return s;
    }

    private static final Object THUMB_LOCK = new Object();

    /** GET /thumb/photos/... — a 480px JPEG thumbnail of a photo,
        generated once and cached in photos/.thumbs (keyed by path +
        source mtime, so a replaced photo gets a fresh thumb). Served
        with immutable cache headers like the originals. */
    private void handleThumb(Socket s, String rel) throws Exception {
        OutputStream out = s.getOutputStream();
        File src = null;
        try { src = photoFile(rel); } catch (Exception ignored) { }
        if (src == null || !src.isFile() || !isImageName(src.getName())) {
            byte[] b = "not found".getBytes("UTF-8");
            writeHead(out, 404, "Not Found", "text/plain", b.length);
            out.write(b); out.flush(); s.close(); return;
        }
        File thumbsDir = new File(ROOT, "photos/.thumbs");
        if (!thumbsDir.exists()) thumbsDir.mkdirs();
        String key = rel.replace('/', '_') + "." + src.lastModified() + ".jpg";
        File thumb = new File(thumbsDir, key);
        if (!thumb.isFile()) {
            synchronized (THUMB_LOCK) {
                if (!thumb.isFile()) makeThumb(src, thumb);
            }
        }
        writeHeadCache(out, 200, "OK", "image/jpeg", thumb.length(),
                "public, max-age=31536000, immutable");
        FileInputStream fis = new FileInputStream(thumb);
        byte[] buf = new byte[32768];
        int n;
        while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
        fis.close();
        out.flush(); s.close();
    }

    private static void makeThumb(File src, File dest) throws Exception {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(src.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new Exception("undecodable");
        int maxDim = Math.max(o.outWidth, o.outHeight);
        int sample = 1;
        while (maxDim / (sample * 2) >= 480) sample *= 2;
        o.inSampleSize = sample;
        o.inJustDecodeBounds = false;
        android.graphics.Bitmap bmp =
                android.graphics.BitmapFactory.decodeFile(src.getAbsolutePath(), o);
        if (bmp == null) throw new Exception("undecodable");
        int deg = 0;
        try {
            android.media.ExifInterface ex =
                    new android.media.ExifInterface(src.getAbsolutePath());
            int ori = ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
            if (ori == android.media.ExifInterface.ORIENTATION_ROTATE_90) deg = 90;
            else if (ori == android.media.ExifInterface.ORIENTATION_ROTATE_180) deg = 180;
            else if (ori == android.media.ExifInterface.ORIENTATION_ROTATE_270) deg = 270;
        } catch (Exception ignored) { }
        int w = bmp.getWidth(), h = bmp.getHeight();
        int m = Math.max(w, h);
        if (m > 480) {
            float sc = 480f / m;
            android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(
                    bmp, Math.round(w * sc), Math.round(h * sc), true);
            if (scaled != bmp) { bmp.recycle(); bmp = scaled; }
        }
        if (deg != 0) {
            android.graphics.Matrix mx = new android.graphics.Matrix();
            mx.postRotate(deg);
            android.graphics.Bitmap rot = android.graphics.Bitmap.createBitmap(
                    bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), mx, true);
            if (rot != bmp) { bmp.recycle(); bmp = rot; }
        }
        File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, fos);
        fos.close();
        bmp.recycle();
        if (!tmp.renameTo(dest)) throw new Exception("thumb write failed");
    }

    private static String lanIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        if (a.isSiteLocalAddress()) return a.getHostAddress();
                        if (fallback == null) fallback = a.getHostAddress();
                    }
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    /** Phone photo upload page. Open any time on the home network
        (Sameer's call 2026-10-25: no getting up to open a window on
        the Portal first — the endpoint only exists on his LAN). The
        upload-open endpoint survives as a no-op that returns the URL. */
    private void handlePhotoAdd(Socket s, InputStream in, Map<String, String> headers,
                                String method) throws Exception {
        s.setSoTimeout(60000);
        OutputStream out = s.getOutputStream();
        if (method.equals("GET")) {
            String html = "<!doctype html><meta name=viewport content='width=device-width'>"
                    + "<body style='font-family:sans-serif;padding:24px;background:#f4ead8;color:#2b2118'>"
                    + "<h2>Add photos to the Portal</h2>"
                    + "<form method='post' enctype='multipart/form-data'>"
                    + "<p><input type='file' name='photos' accept='image/*' multiple "
                    + "style='font-size:19px'></p>"
                    + "<p><button type='submit' style='font-size:20px;padding:12px 28px'>Send</button></p>"
                    + "</form><p style='color:#6f6151'>Up to 15 photos at a time. "
                    + "Pick JPEGs if you can — the Portal "
                    + "can't show iPhone HEIC files. (The Add to Portal shortcut converts "
                    + "automatically.)</p></body>";
            byte[] b = html.getBytes("UTF-8");
            writeHead(out, 200, "OK", "text/html; charset=utf-8", b.length);
            out.write(b); out.flush(); s.close(); return;
        }
        if (!method.equals("POST")) { s.close(); return; }
        String ct = headers.getOrDefault("content-type", "");
        int len = -1;
        try { len = Integer.parseInt(headers.getOrDefault("content-length", "-1")); }
        catch (NumberFormatException ignored) { }
        String boundary = null;
        for (String part : ct.split(";")) {
            part = part.trim();
            if (part.startsWith("boundary=")) boundary = part.substring("boundary=".length());
        }
        if (boundary == null || len < 0 || len > 80_000_000) {
            byte[] b = "bad upload".getBytes("UTF-8");
            writeHead(out, 400, "Bad Request", "text/plain", b.length);
            out.write(b); out.flush(); s.close(); return;
        }
        String expect = headers.get("expect");
        if (expect != null && expect.equalsIgnoreCase("100-continue")) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes("UTF-8"));
            out.flush();
        }
        byte[] data = new byte[len];
        int read = 0;
        while (read < len) {
            int n = in.read(data, read, len - read);
            if (n < 0) break;
            read += n;
        }
        int[] res = savePhotoParts(data, boundary);
        int saved = res[0], skipped = res[1];
        // After an upload, ask before closing (his ask 2026-10-31):
        // Add more returns to the picker; Done swaps to the closing note.
        String html = "<!doctype html><meta name=viewport content='width=device-width'>"
                + "<body style='font-family:sans-serif;padding:24px;background:#f4ead8;color:#2b2118'>"
                + "<div id='ask'>"
                + "<h2>Added " + saved + " photo" + (saved == 1 ? "" : "s") + "</h2>"
                + (skipped > 0
                    ? "<p style='color:#6f6151'>" + skipped + " left off — 15 max per add.</p>"
                    : "")
                + "<p style='font-size:19px'>Add more?</p>"
                + "<p><a href='/photo-add' style='display:inline-block;font-size:20px;"
                + "padding:12px 28px;background:#9a6a1f;color:#fff;text-decoration:none;"
                + "border-radius:8px;margin-right:12px'>Add more</a>"
                + "<button type='button' style='font-size:20px;padding:12px 28px' "
                + "onclick=\"document.getElementById('ask').style.display='none';"
                + "document.getElementById('done').style.display='block'\">Done</button></p></div>"
                + "<div id='done' style='display:none'><h2>All set</h2>"
                + "<p>They're on the Portal now. You can close this tab.</p></div>"
                + "</body>";
        byte[] b = html.getBytes("UTF-8");
        writeHead(out, 200, "OK", "text/html; charset=utf-8", b.length);
        out.write(b); out.flush(); s.close();
    }

    /** Max photos saved per add (his cap 2026-10-31; the picker page
        says the same). Extra file parts are counted as skipped. */
    private static final int MAX_PHOTOS_PER_ADD = 15;

    /** Minimal multipart parser: walks the parts, saves file parts.
        Returns {saved, skipped}. */
    private int[] savePhotoParts(byte[] data, String boundary) {
        int saved = 0, skipped = 0;
        try {
            byte[] marker = ("--" + boundary).getBytes("UTF-8");
            File dir = new File(ROOT, "photos");
            if (!dir.exists()) dir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
            int pos = indexOf(data, marker, 0);
            while (pos >= 0) {
                int partStart = pos + marker.length;
                if (partStart + 1 < data.length && data[partStart] == '-' ) break; // final
                int headerEnd = indexOf(data, "\r\n\r\n".getBytes("UTF-8"), partStart);
                if (headerEnd < 0) break;
                String partHeaders = new String(data, partStart, headerEnd - partStart, "UTF-8");
                int contentStart = headerEnd + 4;
                int next = indexOf(data, marker, contentStart);
                if (next < 0) break;
                int contentEnd = next - 2; // CRLF before the boundary
                String filename = null;
                for (String hLine : partHeaders.split("\r\n")) {
                    if (hLine.toLowerCase(Locale.US).contains("filename=")) {
                        int a = hLine.indexOf("filename=\"");
                        if (a >= 0) {
                            int b2 = hLine.indexOf('"', a + 10);
                            if (b2 > a) filename = hLine.substring(a + 10, b2);
                        }
                    }
                }
                if (filename != null && !filename.isEmpty() && contentEnd > contentStart) {
                    if (saved >= MAX_PHOTOS_PER_ADD) { skipped++; pos = next; continue; }
                    String ext = ".jpg";
                    String low = filename.toLowerCase(Locale.US);
                    for (String e : new String[]{".jpg", ".jpeg", ".png", ".webp", ".gif", ".heic"}) {
                        if (low.endsWith(e)) { ext = e; break; }
                    }
                    File outFile = new File(dir, stamp + "-" + saved + ext);
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        fos.write(data, contentStart, contentEnd - contentStart);
                    }
                    saved++;
                }
                pos = next;
            }
        } catch (Exception ignored) { }
        return new int[]{saved, skipped};
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** Snapshot push: PUT /upload/<path> with Authorization: Bearer <token>. */
    private void handlePut(Socket s, InputStream in, Map<String, String> headers,
                           String path) throws IOException {
        OutputStream out = s.getOutputStream();
        String auth = headers.get("authorization");
        if (auth == null || !auth.equals("Bearer " + TOKEN)) {
            byte[] body = "unauthorized".getBytes("UTF-8");
            writeHead(out, 401, "Unauthorized", "text/plain", body.length);
            out.write(body); out.flush(); s.close(); return;
        }
        String rel = null;
        boolean toPrivate = path.equals("/upload/sa-key.json");
        if (path.startsWith("/upload/") && !toPrivate) {
            String candidate = path.substring("/upload".length());
            for (String allowed : UPLOADABLE) {
                if (allowed.equals(candidate)) { rel = candidate; break; }
            }
        }
        if (rel == null && !toPrivate) {
            byte[] body = "not uploadable".getBytes("UTF-8");
            writeHead(out, 403, "Forbidden", "text/plain", body.length);
            out.write(body); out.flush(); s.close(); return;
        }
        // Honor Expect: 100-continue, or clients stall waiting for it.
        String expect = headers.get("expect");
        if (expect != null && expect.equalsIgnoreCase("100-continue")) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes("UTF-8"));
            out.flush();
        }
        int len = -1;
        try { len = Integer.parseInt(headers.getOrDefault("content-length", "-1")); }
        catch (NumberFormatException ignored) { }
        if (len < 0 || len > 5_000_000) {
            byte[] body = "bad length".getBytes("UTF-8");
            writeHead(out, 411, "Length Required", "text/plain", body.length);
            out.write(body); out.flush(); s.close(); return;
        }
        byte[] data = new byte[len];
        int read = 0;
        while (read < len) {
            int n = in.read(data, read, len - read);
            if (n < 0) break;
            read += n;
        }
        if (read != len) { s.close(); return; }

        File target = toPrivate
                ? new File(getFilesDir(), "sa-key.json")
                : new File(ROOT, rel);
        if (!target.getParentFile().exists()) target.getParentFile().mkdirs();
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        fos.write(data);
        fos.close();
        if (!tmp.renameTo(target)) {
            // Fallback for filesystems that refuse rename-over.
            FileOutputStream direct = new FileOutputStream(target);
            direct.write(data);
            direct.close();
            tmp.delete();
        }
        byte[] body = "ok".getBytes("UTF-8");
        writeHead(out, 200, "OK", "text/plain", body.length);
        out.write(body); out.flush(); s.close();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) break;
        }
        if (!any && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static void writeHead(OutputStream out, int code, String msg,
                                  String type, long len) throws IOException {
        writeHeadCache(out, code, msg, type, len, "no-store");
    }

    private static void writeHeadCache(OutputStream out, int code, String msg,
                                       String type, long len, String cache) throws IOException {
        String head = "HTTP/1.1 " + code + " " + msg + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + len + "\r\n"
                + "Cache-Control: " + cache + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
    }

    private static String mimeFor(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html; charset=utf-8";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".js")) return "text/javascript";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
