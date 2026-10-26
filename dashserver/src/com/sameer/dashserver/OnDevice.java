package com.sameer.dashserver;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Content the dashboard used to receive in the host-built snapshot
 * (data.json), now fetched and cached by the Portal itself:
 *
 *   GET /api/prayer -> {feed_date, today:[{name, azan, iqamah,
 *                        chess_iqamah}]}   (isalaah IOK + CHESS)
 *   GET /api/word   -> {ur, ur_translit, ur_meaning, example, pos,
 *                        audio_ur, source}  (Transparent Language
 *                        Urdu word-of-the-day RSS)
 *
 * Both port the exact source endpoints and shaping that build_data.py
 * and daily_word.py used host-side. Each payload is cached on-device
 * (prayer-cache.json / word-cache.json in the dashboard root) keyed
 * by local date: a same-day cache is served without a fetch, a stale
 * cache is refreshed, and a failed refresh falls back to the cache —
 * the endpoint only errors when there is nothing cached at all.
 *
 * The word's audio is synthesized on-device with Android TTS into
 * audio/wotd-ur.mp3 (lazy: a word-date stamp decides staleness, and
 * synthesis is kicked from /api/word and from requests for the audio
 * file itself). If the device has no TTS engine, the text still
 * works and the failure is only logged — no audio is faked.
 */
public class OnDevice {
    private static final String TAG = "DashServer";
    private static final String ISALAAH =
            "https://isalaah.com/loc/ca/schedule.php?p=clk&loc=";
    private static final String WORD_FEED =
            "https://feeds.feedblitz.com/urdu-word-of-the-day";
    private static final String AUDIO_REL = "audio/wotd-ur.mp3";

    private final Context ctx;

    OnDevice(Context ctx) { this.ctx = ctx; }

    // ------------------------------------------------------------ http ---

    private static String httpGet(String url, String ua) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", ua);
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
        }
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + " for " + url);
        return sb.toString();
    }

    private static String readFile(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
            return sb.toString();
        }
    }

    private static void writeFileAtomic(File target, String text) throws Exception {
        if (!target.getParentFile().exists()) target.getParentFile().mkdirs();
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(target)) {
            try (FileOutputStream fos = new FileOutputStream(target)) {
                fos.write(text.getBytes(StandardCharsets.UTF_8));
            }
            tmp.delete();
        }
    }

    private static String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    /** The cached payload for cacheFile when its stamp is today's,
        any cached payload (stale ok) otherwise, or null. */
    private JSONObject cached(File cacheFile, boolean anyAge) {
        try {
            if (!cacheFile.isFile()) return null;
            JSONObject wrap = new JSONObject(readFile(cacheFile));
            if (!anyAge && !todayKey().equals(wrap.optString("date"))) return null;
            JSONObject data = wrap.optJSONObject("data");
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private void store(File cacheFile, JSONObject data) {
        try {
            JSONObject wrap = new JSONObject()
                    .put("date", todayKey()).put("data", data);
            writeFileAtomic(cacheFile, wrap.toString());
        } catch (Exception e) {
            Log.w(TAG, "on-device cache write failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------- prayer ---

    // (display name, azan key, iqamah key, inferred meridiem)
    private static final String[][] PRAYERS = {
        {"Fajr", "t1", "f", "AM"},
        {"Dhuhr", "t2", "d", "PM"},
        {"Asr", "t3", "a", "PM"},
        {"Maghrib", "t4", "m", "PM"},
        {"Isha", "t5", "i", "PM"},
    };

    /** build_data.py fmt_azan: '5:55:00 AM' -> '5:55 AM'; a bare
        '12:36' gains the inferred meridiem. */
    private static String fmtAzan(String raw, String meridiem) {
        raw = raw.trim();
        String[] parts = raw.split("\\s+");
        if (raw.contains("AM") || raw.contains("PM")) {
            String[] hm = parts[0].split(":");
            return Integer.parseInt(hm[0]) + ":" + hm[1] + " " + parts[1];
        }
        String[] hm = raw.split(":");
        return Integer.parseInt(hm[0]) + ":" + hm[1] + " " + meridiem;
    }

    private static String fmtIqamah(String raw, String meridiem) {
        String[] hm = raw.trim().split(":");
        return Integer.parseInt(hm[0]) + ":" + hm[1] + " " + meridiem;
    }

    private JSONObject fetchPrayer() throws Exception {
        JSONObject iok = new JSONObject(httpGet(ISALAAH + "iok", "portal-dashboard/1.0"));
        JSONObject chess = new JSONObject(httpGet(ISALAAH + "chess", "portal-dashboard/1.0"));
        JSONArray rows = new JSONArray();
        for (String[] p : PRAYERS) {
            String name = p[0], tkey = p[1], ikey = p[2], mer = p[3];
            if (name.equals("Dhuhr")) { // Sunrise sits between Fajr and Dhuhr
                rows.put(new JSONObject()
                        .put("name", "Sunrise")
                        .put("azan", fmtIqamah(iok.getString("s"), "AM"))
                        .put("iqamah", JSONObject.NULL)
                        .put("chess_iqamah", JSONObject.NULL));
            }
            rows.put(new JSONObject()
                    .put("name", name)
                    .put("azan", fmtAzan(iok.getString(tkey), mer))
                    .put("iqamah", fmtIqamah(iok.getString(ikey), mer))
                    .put("chess_iqamah", fmtIqamah(chess.getString(ikey), mer)));
        }
        JSONObject out = new JSONObject();
        out.put("feed_date", iok.has("nf") ? iok.get("nf") : JSONObject.NULL);
        out.put("today", rows);
        return out;
    }

    synchronized JSONObject prayer() throws Exception {
        File cacheFile = new File(DashService.ROOT, "prayer-cache.json");
        JSONObject fresh = cached(cacheFile, false);
        if (fresh != null) return fresh;
        try {
            JSONObject data = fetchPrayer();
            store(cacheFile, data);
            return data;
        } catch (Exception e) {
            JSONObject stale = cached(cacheFile, true);
            if (stale != null) {
                Log.w(TAG, "prayer refresh failed (" + e.getMessage()
                        + "); serving on-device cache");
                return stale;
            }
            throw e;
        }
    }

    // ------------------------------------------------------------ word ---

    private static final Pattern ITEM =
            Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern TITLE =
            Pattern.compile("<title>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</title>",
                    Pattern.DOTALL);
    private static final Pattern TRANSLIT =
            Pattern.compile("<wotd:transliteratedWord>(.*?)</wotd:transliteratedWord>",
                    Pattern.DOTALL);
    private static final Pattern CDATA =
            Pattern.compile("<!\\[CDATA\\[(.*?)\\]\\]>", Pattern.DOTALL);
    private static final Pattern TAG_RE = Pattern.compile("<[^>]+>");
    private static final Pattern ENTITY =
            Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|nbsp);");
    private static final Pattern POS =
            Pattern.compile("Part of speech\\s*:\\s*([A-Za-z]+)");
    private static final Pattern EXAMPLE =
            Pattern.compile("Example sentence\\s*:\\s*(.+?)\\s*Sentence meaning",
                    Pattern.DOTALL);
    private static final Pattern ARABIC = Pattern.compile("[\\u0600-\\u06FF]");

    /** Single-pass HTML entity unescape (daily_word.py used
        html.unescape; this covers the entities the feed emits). */
    private static String unescape(String s) {
        Matcher m = ENTITY.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String e = m.group(1);
            String rep;
            switch (e) {
                case "amp": rep = "&"; break;
                case "lt": rep = "<"; break;
                case "gt": rep = ">"; break;
                case "quot": rep = "\""; break;
                case "apos": rep = "'"; break;
                case "nbsp": rep = " "; break;
                default:
                    try {
                        int cp = e.startsWith("#x")
                                ? Integer.parseInt(e.substring(2), 16)
                                : Integer.parseInt(e.substring(1));
                        rep = new String(Character.toChars(cp));
                    } catch (Exception ex) { rep = ""; }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** daily_word.py item_parts + parse_wotd for the Urdu feed:
        title carries 'WORD: translation'; the body tables carry
        part of speech / example / meaning; a transliteration rides
        in <wotd:transliteratedWord> when the feed provides one. */
    private JSONObject fetchWord() throws Exception {
        // FeedBlitz sniffs the UA: a browser UA gets the human
        // subscribe page, a plain one gets the XML. Short on purpose.
        String xml = httpGet(WORD_FEED, "Mozilla/5.0");
        Matcher im = ITEM.matcher(xml);
        if (!im.find()) throw new Exception("no <item> in word feed");
        String block = im.group(1);
        Matcher tm = TITLE.matcher(block);
        if (!tm.find()) throw new Exception("no title in word feed item");
        String title = unescape(tm.group(1)).trim();
        Matcher rm = TRANSLIT.matcher(block);
        String translit = rm.find() ? unescape(rm.group(1)).trim() : "";
        String raw = CDATA.matcher(block).replaceAll("$1");
        raw = unescape(TAG_RE.matcher(raw).replaceAll(" "));
        String body = raw.replaceAll("\\s+", " ").trim();

        int colon = title.indexOf(':');
        if (colon < 0) throw new Exception("word feed title has no pair colon");
        String word = title.substring(0, colon).trim();
        // Python str.strip(" -–—"): trim those chars from both ends.
        word = word.replaceAll("^[ \\-\\u2013\\u2014]+", "")
                   .replaceAll("[ \\-\\u2013\\u2014]+$", "");
        String meaning = title.substring(colon + 1).trim();
        if (word.isEmpty() || !ARABIC.matcher(word).find()) {
            throw new Exception("Urdu word failed the Arabic-script check");
        }
        Matcher pm = POS.matcher(body);
        Matcher em = EXAMPLE.matcher(body);
        return new JSONObject()
                .put("ur", word)
                .put("ur_translit", translit)
                .put("ur_meaning", meaning)
                .put("example", em.find() ? em.group(1).trim() : "")
                .put("pos", pm.find() ? pm.group(1) : "")
                .put("audio_ur", AUDIO_REL)
                .put("source", "transparent");
    }

    synchronized JSONObject word() throws Exception {
        File cacheFile = new File(DashService.ROOT, "word-cache.json");
        JSONObject data = cached(cacheFile, false);
        if (data == null) {
            try {
                data = fetchWord();
                store(cacheFile, data);
            } catch (Exception e) {
                data = cached(cacheFile, true);
                if (data == null) throw e;
                Log.w(TAG, "word refresh failed (" + e.getMessage()
                        + "); serving on-device cache");
            }
        }
        kickAudio(data);
        return data;
    }

    // ------------------------------------------------------------- tts ---

    private TextToSpeech tts;
    private boolean ttsInitFailed = false;
    private String pendingSpoken;
    private File pendingOut;

    private File audioFile() { return new File(DashService.ROOT, AUDIO_REL); }
    private File audioStamp() {
        return new File(DashService.ROOT, "audio/wotd-ur.stamp");
    }

    /** Lazy on-device synthesis of today's word clip. The stamp
        holds the word-cache date the current clip was made for; a
        mismatch (or a missing clip) kicks synthesis. Failures —
        including "no TTS engine on this device" — are logged only;
        the word text is unaffected and no audio is faked. */
    synchronized void kickAudio(JSONObject wordData) {
        try {
            String stamp = null;
            File sf = audioStamp();
            if (sf.isFile()) stamp = readFile(sf).trim();
            if (todayKey().equals(stamp) && audioFile().isFile()) return;
            String ur = wordData.optString("ur", "");
            if (ur.isEmpty()) return;
            String example = wordData.optString("example", "");
            // daily_word.py's spoken line: word, word, example.
            String spoken = ur + "\u06D4 " + ur + "\u06D4 " + example;
            pendingSpoken = spoken;
            pendingOut = audioFile();
            if (ttsInitFailed) return;
            if (tts != null) { synthesize(); return; }
            tts = new TextToSpeech(ctx, status -> {
                synchronized (OnDevice.this) {
                    if (status != TextToSpeech.SUCCESS) {
                        ttsInitFailed = true;
                        Log.w(TAG, "TTS init failed (status " + status
                                + ") — no TTS engine on this device;"
                                + " word audio stays unsynthesized");
                        return;
                    }
                    int r = tts.setLanguage(new Locale("ur"));
                    if (r == TextToSpeech.LANG_MISSING_DATA
                            || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        r = tts.setLanguage(new Locale("ur", "PK"));
                    }
                    Log.i(TAG, "TTS engine ready: " + tts.getDefaultEngine()
                            + ", ur availability=" + r);
                    synthesize();
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "word audio kick failed: " + e.getMessage());
        }
    }

    /** Called from DashService when the audio file itself is
        requested, so a clip request can also trigger synthesis. */
    void kickAudioIfStale() {
        try {
            JSONObject w = cached(new File(DashService.ROOT, "word-cache.json"), true);
            if (w != null) kickAudio(w);
        } catch (Exception ignored) { }
    }

    private void synthesize() {
        if (tts == null || pendingSpoken == null || pendingOut == null) return;
        try {
            if (!pendingOut.getParentFile().exists()) pendingOut.getParentFile().mkdirs();
            File tmp = new File(pendingOut.getParentFile(), "wotd-ur.tmp.mp3");
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) {
                    try {
                        File done = new File(pendingOut.getParentFile(), "wotd-ur.tmp.mp3");
                        if (done.isFile() && done.length() > 0) {
                            if (!done.renameTo(pendingOut)) {
                                try (FileInputStream in = new FileInputStream(done);
                                     FileOutputStream out = new FileOutputStream(pendingOut)) {
                                    byte[] buf = new byte[32768];
                                    int n;
                                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                                }
                                done.delete();
                            }
                            writeFileAtomic(audioStamp(), todayKey());
                            Log.i(TAG, "word audio synthesized on-device: "
                                    + pendingOut.length() + " bytes");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "word audio finalize failed: " + e.getMessage());
                    }
                }
                @Override public void onError(String id) {
                    Log.w(TAG, "word audio synthesis error for utterance " + id);
                }
            });
            int r = tts.synthesizeToFile(pendingSpoken, new Bundle(), tmp,
                    "wotd-ur");
            if (r != TextToSpeech.SUCCESS) {
                Log.w(TAG, "synthesizeToFile refused (result " + r + ")");
            }
        } catch (Exception e) {
            Log.w(TAG, "word audio synthesis failed: " + e.getMessage());
        }
    }
}
