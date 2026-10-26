package com.sameer.dashserver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Portal's live Google endpoints, served by DashService:
 *
 *   GET  /api/grocery            -> {now:[{item,row}], later:[...]}
 *   POST /api/grocery/checkoff   -> {col:"A"|"B", row:3} (clears the cell)
 *   POST /api/grocery/add        -> {item:"eggs", col:"A"}
 *   GET  /api/agenda             -> {agenda:[{time, title}]}
 *   GET  /api/calendar?timeMin=<rfc3339>&timeMax=<rfc3339>
 *                                -> {events:[{start, end, title, allday,
 *                                     location, cal, gid?}]}
 *   POST /api/calendar/update    -> {id, title, start, end, allday}
 *   POST /api/calendar/delete    -> {id}
 *
 * The service account may write exactly one calendar — the Family
 * calendar (shared to it as writer). Event creation inserts there
 * directly (insertFamilyEvent below, called from DashService's
 * /api/calendar/add); any failure falls back to the on-device queue
 * in DashService, which the calendar read retries on-device via the
 * same insert path (DashService's flushQueue). Sameer's
 * primary calendar is deliberately not shared and never touched.
 * The read carries a "gid" (Google event id) ONLY on Family-calendar
 * events; update/delete hard-code the Family calendar in the Google
 * path, so the page can edit exactly those events and no others —
 * an id from any other calendar simply does not resolve there.
 *
 * Backed by the service-account key (see GoogleClient). Without the
 * key, everything answers 501 and the page uses its snapshot instead.
 *
 * Sheet conventions (Sameer's): column A = Now, column B = Later.
 * Checking an item off means "we got it, that's it": the item's cell
 * is cleared outright — value and any fill — and it leaves the list.
 * There is no marked/bought state (the old green-fill toggle was
 * retired in 6.3).
 */
public class PortalApi {
    static final String SHEET_ID = "102Y_rnhGDcoMy6uIry_8aLPsILLWPQcoobf4vETAg3M";
    static final String TAB = "Grocery list";
    private static final String SHEETS = "https://sheets.googleapis.com/v4/spreadsheets/" + SHEET_ID;
    private static final String CAL = "https://www.googleapis.com/calendar/v3";
    /** The Family calendar — the only calendar shared with the
        service account (as writer). Native adds go here. */
    static final String FAMILY_CAL_ID =
            "family07997508299873499104@group.calendar.google.com";

    private final GoogleClient google;
    private Integer sheetGid;
    private Integer usualsGid;

    PortalApi(GoogleClient google) { this.google = google; }

    static class Result {
        final int status; final String body;
        Result(int s, String b) { status = s; body = b; }
    }

    Result handle(String method, String path, String query, String body) {
        try {
            if (!google.hasKey()) return err(501, "no key on device yet");
            switch (path) {
                case "/api/grocery":
                    if (method.equals("GET")) return ok(grocery());
                    break;
                case "/api/grocery/checkoff":
                    if (method.equals("POST")) { checkoff(new JSONObject(body)); return ok(new JSONObject().put("ok", true)); }
                    break;
                case "/api/grocery/add":
                    if (method.equals("POST")) { add(new JSONObject(body)); return ok(new JSONObject().put("ok", true)); }
                    break;
                case "/api/grocery/delete":
                    if (method.equals("POST")) { delete(new JSONObject(body)); return ok(new JSONObject().put("ok", true)); }
                    break;
                case "/api/usuals":
                    if (method.equals("GET")) return ok(usuals());
                    break;
                case "/api/usuals/add":
                    if (method.equals("POST")) { addUsual(new JSONObject(body)); return ok(new JSONObject().put("ok", true)); }
                    break;
                case "/api/usuals/remove":
                    if (method.equals("POST")) { removeUsual(new JSONObject(body)); return ok(new JSONObject().put("ok", true)); }
                    break;
                case "/api/agenda":
                    if (method.equals("GET")) return ok(agenda());
                    break;
                case "/api/calendar":
                    if (method.equals("GET")) return ok(calendar(parseQuery(query)));
                    break;
                case "/api/calendar/update":
                    if (method.equals("POST")) { updateFamilyEvent(new JSONObject(body)); return ok(new JSONObject().put("ok", true)); }
                    break;
                case "/api/calendar/delete":
                    if (method.equals("POST")) { deleteFamilyEvent(new JSONObject(body)); return ok(new JSONObject().put("ok", true)); }
                    break;
                default:
                    return err(404, "unknown api path");
            }
            return err(405, "method not allowed");
        } catch (GoogleClient.KeyMissing e) {
            return err(501, "no key on device yet");
        } catch (Exception e) {
            return err(502, String.valueOf(e.getMessage()));
        }
    }

    private Result ok(JSONObject j) { return new Result(200, j.toString()); }
    private Result err(int s, String msg) {
        try { return new Result(s, new JSONObject().put("error", msg).toString()); }
        catch (Exception e) { return new Result(s, "{\"error\":\"api error\"}"); }
    }

    // ---------- grocery: read ----------

    private JSONObject grocery() throws Exception {
        String range = URLEncoder.encode("'" + TAB + "'!A1:B80", "UTF-8");
        String url = SHEETS + "?ranges=" + range + "&includeGridData=true"
                + "&fields=sheets.properties,sheets.data.rowData.values(formattedValue)";
        JSONObject root = new JSONObject(google.get(url));
        JSONArray sheets = root.getJSONArray("sheets");
        JSONArray now = new JSONArray(), later = new JSONArray();
        for (int s = 0; s < sheets.length(); s++) {
            JSONObject sheet = sheets.getJSONObject(s);
            JSONObject props = sheet.getJSONObject("properties");
            if (!TAB.equals(props.optString("title"))) continue;
            sheetGid = props.getInt("sheetId");
            JSONArray rowData = sheet.getJSONArray("data").getJSONObject(0)
                    .optJSONArray("rowData");
            if (rowData == null) break;
            for (int r = 0; r < rowData.length(); r++) {
                JSONArray vals = rowData.getJSONObject(r).optJSONArray("values");
                if (vals == null) continue;
                put(vals, 0, r + 1, now);
                put(vals, 1, r + 1, later);
            }
        }
        return new JSONObject().put("now", now).put("later", later);
    }

    private void put(JSONArray vals, int col, int row, JSONArray out) throws Exception {
        if (vals.length() <= col) return;
        JSONObject cell = vals.getJSONObject(col);
        String v = cell.optString("formattedValue", "").trim();
        if (v.isEmpty()) return;
        out.put(new JSONObject().put("item", v).put("row", row));
    }

    // ---------- grocery: writes ----------

    /** Check-off = "we got it, that's it": clear the cell's value and
        clear any fill, so the cell goes back to blank. The item
        leaves the list on the next read; there is nothing to undo
        because there is no marked state to restore. */
    private void checkoff(JSONObject req) throws Exception {
        if (sheetGid == null) grocery(); // learn the tab id first
        String colLetter = "B".equals(req.optString("col", "A")) ? "B" : "A";
        int col = "B".equals(colLetter) ? 1 : 0;
        int row = req.getInt("row");
        // 1) clear the value (same write the delete path uses)
        String range = "'" + TAB + "'!" + colLetter + row;
        JSONObject vbody = new JSONObject()
                .put("range", range)
                .put("values", new JSONArray().put(new JSONArray().put("")));
        google.putJson(SHEETS + "/values/" + URLEncoder.encode(range, "UTF-8")
                + "?valueInputOption=RAW", vbody.toString());
        // 2) clear any fill: naming the field in the mask while the
        //    cell carries no backgroundColor clears it to no-fill.
        JSONObject grange = new JSONObject()
                .put("sheetId", sheetGid)
                .put("startRowIndex", row - 1).put("endRowIndex", row)
                .put("startColumnIndex", col).put("endColumnIndex", col + 1);
        JSONObject repeat = new JSONObject()
                .put("range", grange)
                .put("cell", new JSONObject().put("userEnteredFormat", new JSONObject()))
                .put("fields", "userEnteredFormat.backgroundColor");
        JSONObject body = new JSONObject().put("requests",
                new JSONArray().put(new JSONObject().put("repeatCell", repeat)));
        google.postJson(SHEETS + ":batchUpdate", body.toString());
    }

    private void add(JSONObject req) throws Exception {
        String item = req.getString("item").trim();
        if (item.isEmpty()) throw new Exception("empty item");
        boolean later = "B".equals(req.optString("col", "A"));
        JSONArray rowVals = later
                ? new JSONArray().put("").put(item)
                : new JSONArray().put(item).put("");
        JSONObject body = new JSONObject().put("values", new JSONArray().put(rowVals));
        String range = URLEncoder.encode("'" + TAB + "'!A:B", "UTF-8");
        google.postJson(SHEETS + "/values/" + range
                + ":append?valueInputOption=RAW&insertDataOption=INSERT_ROWS",
                body.toString());
    }

    private void delete(JSONObject req) throws Exception {
        String col = "B".equals(req.optString("col", "A")) ? "B" : "A";
        int row = req.getInt("row");
        String range = "'" + TAB + "'!" + col + row;
        JSONObject body = new JSONObject()
                .put("range", range)
                .put("values", new JSONArray().put(new JSONArray().put("")));
        google.putJson(SHEETS + "/values/" + URLEncoder.encode(range, "UTF-8")
                + "?valueInputOption=RAW", body.toString());
    }

    // ---------- usuals (quick-add catalog) ----------

    /** "Usuals" tab: column A = category, column B = item. Created and
        seeded on first read if it doesn't exist (the SA is an editor
        on the workbook), so the feature self-bootstraps. */
    private JSONObject usuals() throws Exception {
        JSONArray values;
        try {
            String range = URLEncoder.encode("Usuals!A1:B200", "UTF-8");
            JSONObject resp = new JSONObject(google.get(SHEETS + "/values/" + range));
            values = resp.optJSONArray("values");
        } catch (Exception e) {
            seedUsuals();
            String range = URLEncoder.encode("Usuals!A1:B200", "UTF-8");
            JSONObject resp = new JSONObject(google.get(SHEETS + "/values/" + range));
            values = resp.optJSONArray("values");
        }
        // Group by category, preserving sheet order.
        List<String> order = new ArrayList<>();
        java.util.Map<String, JSONArray> byCat = new java.util.LinkedHashMap<>();
        if (values != null) {
            for (int i = 0; i < values.length(); i++) {
                JSONArray rowArr = values.optJSONArray(i);
                if (rowArr == null || rowArr.length() < 2) continue;
                String cat = rowArr.optString(0, "").trim();
                String item = rowArr.optString(1, "").trim();
                if (cat.isEmpty() || item.isEmpty()) continue;
                if (cat.equalsIgnoreCase("Category")) continue; // header
                JSONArray items = byCat.get(cat);
                if (items == null) { items = new JSONArray(); byCat.put(cat, items); order.add(cat); }
                items.put(item);
            }
        }
        JSONArray cats = new JSONArray();
        for (String name : order) {
            cats.put(new JSONObject().put("name", name).put("items", byCat.get(name)));
        }
        return new JSONObject().put("categories", cats);
    }

    private void addUsual(JSONObject req) throws Exception {
        String cat = req.getString("category").trim();
        String item = req.getString("item").trim();
        if (cat.isEmpty() || item.isEmpty()) throw new Exception("empty usual");
        JSONObject body = new JSONObject().put("values",
                new JSONArray().put(new JSONArray().put(cat).put(item)));
        String range = URLEncoder.encode("Usuals!A:B", "UTF-8");
        google.postJson(SHEETS + "/values/" + range
                + ":append?valueInputOption=RAW&insertDataOption=INSERT_ROWS",
                body.toString());
    }

    private void removeUsual(JSONObject req) throws Exception {
        String cat = req.getString("category").trim();
        String item = req.getString("item").trim();
        String range = URLEncoder.encode("Usuals!A1:B200", "UTF-8");
        JSONObject resp = new JSONObject(google.get(SHEETS + "/values/" + range));
        JSONArray values = resp.optJSONArray("values");
        int row = -1;
        if (values != null) {
            for (int i = 0; i < values.length(); i++) {
                JSONArray r = values.optJSONArray(i);
                if (r == null || r.length() < 2) continue;
                if (r.optString(0, "").trim().equalsIgnoreCase(cat)
                        && r.optString(1, "").trim().equalsIgnoreCase(item)) {
                    row = i + 1;
                    break;
                }
            }
        }
        if (row < 0) throw new Exception("usual not found");
        if (usualsGid == null) {
            JSONObject meta = new JSONObject(google.get(SHEETS + "?fields=sheets.properties"));
            JSONArray sh = meta.getJSONArray("sheets");
            for (int i = 0; i < sh.length(); i++) {
                JSONObject p = sh.getJSONObject(i).getJSONObject("properties");
                if ("Usuals".equals(p.optString("title"))) usualsGid = p.getInt("sheetId");
            }
        }
        JSONObject dim = new JSONObject().put("sheetId", usualsGid)
                .put("dimension", "ROWS")
                .put("startIndex", row - 1).put("endIndex", row);
        JSONObject body = new JSONObject().put("requests", new JSONArray().put(
                new JSONObject().put("deleteDimension",
                        new JSONObject().put("range", dim))));
        google.postJson(SHEETS + ":batchUpdate", body.toString());
    }

    private void seedUsuals() throws Exception {
        JSONObject addSheet = new JSONObject().put("requests", new JSONArray().put(
                new JSONObject().put("addSheet", new JSONObject().put("properties",
                        new JSONObject().put("title", "Usuals")))));
        google.postJson(SHEETS + ":batchUpdate", addSheet.toString());
        String[][] seed = {
            {"Category", "Item"},
            {"Pantry", "Apple cider vinegar"},
            {"Personal care", "Pads"},
            {"Household", "Metal spatula"},
            {"Treats", "Lindt roll"},
        };
        JSONArray vals = new JSONArray();
        for (String[] r : seed) vals.put(new JSONArray().put(r[0]).put(r[1]));
        JSONObject body = new JSONObject().put("range", "Usuals!A1").put("values", vals);
        google.putJson(SHEETS + "/values/" + URLEncoder.encode("Usuals!A1", "UTF-8")
                + "?valueInputOption=RAW", body.toString());
    }

    // ---------- calendar ----------

    /** Guards the calendarList repair below to at most once per
        server run. */
    private boolean familyListInsertTried = false;

    /** The account's calendar list, with one repair: a calendar
        shared to a service account can take a very long time to
        surface in calendarList on its own, so if the Family calendar
        is absent, insert it once and re-read. Any failure here is
        swallowed — the read must go on regardless (the page's
        snapshot fallback covers a still-missing calendar). */
    private JSONArray calendarListItems() throws Exception {
        JSONObject list = new JSONObject(google.get(CAL + "/users/me/calendarList"));
        JSONArray items = list.optJSONArray("items");
        if (!familyListInsertTried) {
            familyListInsertTried = true;
            boolean present = false;
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    if (FAMILY_CAL_ID.equals(
                            items.getJSONObject(i).optString("id"))) {
                        present = true;
                        break;
                    }
                }
            }
            if (!present) {
                try {
                    google.postJson(CAL + "/users/me/calendarList",
                            new JSONObject().put("id", FAMILY_CAL_ID).toString());
                    JSONObject again = new JSONObject(
                            google.get(CAL + "/users/me/calendarList"));
                    JSONArray againItems = again.optJSONArray("items");
                    if (againItems != null) items = againItems;
                } catch (Exception ignored) { }
            }
        }
        // Whether or not it surfaced above (the insert needs a wider
        // scope than this client requests, and natural surfacing can
        // lag for days), guarantee the Family calendar is in the
        // returned list: it is the one calendar certain to be shared
        // with this account, and events can be queried by id directly
        // — calendarList membership is not required for that. The
        // accessRole mirrors the actual share (writer).
        boolean present = false;
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                if (FAMILY_CAL_ID.equals(items.getJSONObject(i).optString("id"))) {
                    present = true;
                    break;
                }
            }
        }
        if (!present) {
            try {
                if (items == null) items = new JSONArray();
                items.put(new JSONObject()
                        .put("id", FAMILY_CAL_ID)
                        .put("accessRole", "writer"));
            } catch (Exception ignored) { }
        }
        return items;
    }

    /** Insert an event directly into the Family calendar. Throws on
        any failure; the caller (DashService /api/calendar/add) falls
        back to the on-device queue so no add is ever lost. Returns
        Google's created-event JSON. Event shape matches the page's:
        {title, start, end, allday, location?} with RFC 3339 start/end
        for timed events and plain dates for all-day ones. */
    /** The Google event resource for the page's event shape
        {title, start, end, allday, location?}. Shared by insert and
        update; as a PATCH body it touches only these fields, so an
        event's location/description survive an edit — the page's
        form has no location field and never sends one. */
    private JSONObject eventBody(JSONObject ev) throws Exception {
        JSONObject body = new JSONObject();
        body.put("summary", ev.getString("title"));
        JSONObject start = new JSONObject(), end = new JSONObject();
        if (ev.optBoolean("allday", false)) {
            start.put("date", ev.getString("start"));
            end.put("date", ev.getString("end"));
        } else {
            start.put("dateTime", ev.getString("start"));
            start.put("timeZone", "America/Los_Angeles");
            end.put("dateTime", ev.getString("end"));
            end.put("timeZone", "America/Los_Angeles");
        }
        body.put("start", start);
        body.put("end", end);
        String loc = ev.optString("location", "");
        if (!loc.isEmpty()) body.put("location", loc);
        return body;
    }

    JSONObject insertFamilyEvent(JSONObject ev) throws Exception {
        String id = URLEncoder.encode(FAMILY_CAL_ID, "UTF-8");
        return new JSONObject(google.postJson(
                CAL + "/calendars/" + id + "/events",
                eventBody(ev).toString()));
    }

    /** Patch one event in the Family calendar. The calendar id in
        the path is always Family's; an id minted by any other
        calendar cannot resolve under it, so no other calendar's
        events are reachable through this endpoint. */
    void updateFamilyEvent(JSONObject ev) throws Exception {
        String gid = ev.optString("id", "").trim();
        String title = ev.optString("title", "").trim();
        String start = ev.optString("start", "").trim();
        String end = ev.optString("end", "").trim();
        if (gid.isEmpty() || title.isEmpty() || start.isEmpty() || end.isEmpty()) {
            throw new Exception("bad event");
        }
        String cal = URLEncoder.encode(FAMILY_CAL_ID, "UTF-8");
        String id = URLEncoder.encode(gid, "UTF-8");
        google.patchJson(CAL + "/calendars/" + cal + "/events/" + id,
                eventBody(ev).toString());
        android.util.Log.i("DashServer", "calendar update: Family event "
                + gid + " patched");
    }

    /** Delete one event from the Family calendar (same path rule
        as update). */
    void deleteFamilyEvent(JSONObject ev) throws Exception {
        String gid = ev.optString("id", "").trim();
        if (gid.isEmpty()) throw new Exception("bad event");
        String cal = URLEncoder.encode(FAMILY_CAL_ID, "UTF-8");
        String id = URLEncoder.encode(gid, "UTF-8");
        google.delete(CAL + "/calendars/" + cal + "/events/" + id);
        android.util.Log.i("DashServer", "calendar delete: Family event "
                + gid + " deleted");
    }

    /** Parse an RFC 3339 timestamp. The page sends ISO strings with
        fractional seconds (Date.toISOString), which the plain
        pattern rejects — strip the fraction first. That rejection
        silently 502'd every page-originated calendar read while
        the snapshot covered for it; it surfaced the moment the
        snapshot merge retired. */
    private static Date parseRfc(SimpleDateFormat rfc, String s) throws Exception {
        return rfc.parse(s.replace("Z", "+00:00").replaceAll("\\.\\d+", ""));
    }

    private JSONObject agenda() throws Exception {
        JSONArray items = calendarListItems();
        Calendar day = Calendar.getInstance();
        day.set(Calendar.HOUR_OF_DAY, 0); day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0); day.set(Calendar.MILLISECOND, 0);
        Date start = day.getTime();
        day.add(Calendar.DATE, 1);
        Date end = day.getTime();
        SimpleDateFormat rfc = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        String tMin = URLEncoder.encode(rfc.format(start), "UTF-8");
        String tMax = URLEncoder.encode(rfc.format(end), "UTF-8");

        List<JSONObject> found = new ArrayList<>();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject calItem = items.getJSONObject(i);
                String role = calItem.optString("accessRole", "");
                if (!(role.equals("owner") || role.equals("writer") || role.equals("reader"))) continue;
                String id = URLEncoder.encode(calItem.getString("id"), "UTF-8");
                JSONObject evs = new JSONObject(google.get(CAL + "/calendars/" + id
                        + "/events?singleEvents=true&orderBy=startTime"
                        + "&timeMin=" + tMin + "&timeMax=" + tMax));
                JSONArray evItems = evs.optJSONArray("items");
                if (evItems == null) continue;
                for (int e = 0; e < evItems.length(); e++) {
                    JSONObject ev = evItems.getJSONObject(e);
                    if ("cancelled".equals(ev.optString("status"))) continue;
                    JSONObject st = ev.getJSONObject("start");
                    JSONObject out = new JSONObject();
                    if (st.has("dateTime")) {
                        Date when = parseRfc(new SimpleDateFormat(
                                "yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US),
                                st.getString("dateTime"));
                        out.put("time", new SimpleDateFormat("h:mm a", Locale.US).format(when));
                        out.put("_sort", when.getTime());
                    } else {
                        out.put("time", "All day");
                        out.put("_sort", start.getTime());
                    }
                    out.put("title", ev.optString("summary", "(no title)"));
                    found.add(out);
                }
            }
        }
        found.sort(Comparator.comparingLong(o -> o.optLong("_sort", 0)));
        JSONArray agenda = new JSONArray();
        for (JSONObject o : found) {
            o.remove("_sort");
            agenda.put(o);
        }
        return new JSONObject().put("agenda", agenda);
    }

    private static Map<String, String> parseQuery(String query) throws Exception {
        Map<String, String> out = new HashMap<>();
        if (query == null || query.isEmpty()) return out;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            out.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                    URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
        }
        return out;
    }

    /** Week-view events for [timeMin, timeMax): raw Google start/end
        values pass through untouched (RFC 3339 for timed events, plain
        dates for all-day), so the page renders them with its own clock.
        Follows the agenda() calendar rule: every calendar on the list
        the account can actually read. */
    private JSONObject calendar(Map<String, String> q) throws Exception {
        SimpleDateFormat rfc = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        Date start, end;
        String tMin = q.get("timeMin"), tMax = q.get("timeMax");
        if (tMin != null && tMax != null) {
            start = parseRfc(rfc, tMin);
            end = parseRfc(rfc, tMax);
        } else {
            Calendar day = Calendar.getInstance();
            day.set(Calendar.HOUR_OF_DAY, 0); day.set(Calendar.MINUTE, 0);
            day.set(Calendar.SECOND, 0); day.set(Calendar.MILLISECOND, 0);
            day.add(Calendar.DATE, -day.get(Calendar.DAY_OF_WEEK) + 1);
            start = day.getTime();
            day.add(Calendar.DATE, 7);
            end = day.getTime();
        }
        String tMinE = URLEncoder.encode(rfc.format(start), "UTF-8");
        String tMaxE = URLEncoder.encode(rfc.format(end), "UTF-8");

        JSONArray items = calendarListItems();
        List<JSONObject> found = new ArrayList<>();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject calItem = items.getJSONObject(i);
                String role = calItem.optString("accessRole", "");
                if (!(role.equals("owner") || role.equals("writer") || role.equals("reader"))) continue;
                String rawId = calItem.getString("id");
                boolean isFamily = FAMILY_CAL_ID.equals(rawId);
                String calName = calItem.optString("summary", "");
                if (calName.isEmpty() && isFamily) calName = "Family";
                String id = URLEncoder.encode(rawId, "UTF-8");
                String pageToken = null;
                for (int page = 0; page < 3; page++) {
                    String url = CAL + "/calendars/" + id
                            + "/events?singleEvents=true&orderBy=startTime&maxResults=250"
                            + "&timeMin=" + tMinE + "&timeMax=" + tMaxE
                            + (pageToken != null
                                    ? "&pageToken=" + URLEncoder.encode(pageToken, "UTF-8") : "");
                    JSONObject evs = new JSONObject(google.get(url));
                    JSONArray evItems = evs.optJSONArray("items");
                    if (evItems != null) {
                        for (int e = 0; e < evItems.length(); e++) {
                            JSONObject ev = evItems.getJSONObject(e);
                            if ("cancelled".equals(ev.optString("status"))) continue;
                            JSONObject st = ev.optJSONObject("start");
                            JSONObject en = ev.optJSONObject("end");
                            if (st == null || en == null) continue;
                            JSONObject out = new JSONObject();
                            out.put("title", ev.optString("summary", "(no title)"));
                            out.put("location", ev.optString("location", ""));
                            if (!calName.isEmpty()) out.put("cal", calName);
                            // The Google id rides along ONLY for the
                            // Family calendar — it is what makes an
                            // event editable from the page (update and
                            // delete resolve ids under Family's path).
                            if (isFamily) {
                                String gid = ev.optString("id", "");
                                if (!gid.isEmpty()) out.put("gid", gid);
                            }
                            long sort;
                            if (st.has("dateTime") && en.has("dateTime")) {
                                out.put("start", st.getString("dateTime"));
                                out.put("end", en.getString("dateTime"));
                                out.put("allday", false);
                                sort = parseRfc(rfc, st.getString("dateTime")).getTime();
                            } else if (st.has("date") && en.has("date")) {
                                out.put("start", st.getString("date"));
                                out.put("end", en.getString("date"));
                                out.put("allday", true);
                                sort = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                                        .parse(st.getString("date")).getTime();
                            } else continue;
                            out.put("_sort", sort);
                            found.add(out);
                        }
                    }
                    pageToken = evs.optString("nextPageToken", null);
                    if (pageToken == null || pageToken.isEmpty()) break;
                }
            }
        }
        found.sort(Comparator.comparingLong(o -> o.optLong("_sort", 0)));
        JSONArray events = new JSONArray();
        for (JSONObject o : found) {
            o.remove("_sort");
            events.put(o);
        }
        return new JSONObject().put("events", events);
    }
}
