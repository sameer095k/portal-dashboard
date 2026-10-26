#!/usr/bin/env python3
"""Build data.json for the Portal kitchen dashboard.

Fetches prayer times (isalaah IOK + CHESS), weather (Open-Meteo),
grocery list (Google Sheets via hatch_gws_cli) and today's agenda
(Google Calendar via hatch_gws_cli). Each source fails independently:
on error the section is null and the error is logged to stderr.
Stdlib only.

Two calendar extras for the week view:
- drain_queue(): events added on the Portal itself wait in an
  on-device queue (DashServer /api/calendar/add) because the Portal's
  service-account key is calendar read-only. Draining writes them to
  Google Calendar with this script's credentials (gws) and dequeues
  them on the device. Runs first, so a drained event appears in the
  same run's snapshot.
- get_calweeks(): a +/-5-week events window (data.json "calweeks")
  the page falls back to when the Portal's live /api/calendar read
  is unavailable.
"""

import json
import shlex
import subprocess
import sys
import urllib.request
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

LOCAL_TZ = ZoneInfo("America/Los_Angeles")

BASE = Path(__file__).resolve().parent
OUT = BASE / "data.json"
WORD_FILE = BASE / "word.json"
GWS = "/opt/hatch/bin/hatch_gws_cli"
UA = {"User-Agent": "portal-dashboard/1.0"}


def log_err(section, exc):
    print(f"[build_data] {section} failed: {exc!r}", file=sys.stderr)


def http_json(url, timeout=20):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def run_cli(args, timeout=60):
    proc = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    if proc.returncode != 0:
        raise RuntimeError(f"exit {proc.returncode}: {proc.stderr.strip()[:300]}")
    return json.loads(proc.stdout)


# ---------------------------------------------------------------- prayer ---

PRAYERS = [  # (display name, azan key, iqamah key, iqamah meridiem)
    ("Fajr", "t1", "f", "AM"),
    ("Dhuhr", "t2", "d", "PM"),
    ("Asr", "t3", "a", "PM"),
    ("Maghrib", "t4", "m", "PM"),
    ("Isha", "t5", "i", "PM"),
]


def fmt_azan(raw, meridiem):
    """'5:55:00 AM' -> '5:55 AM'; bare '12:36' -> '12:36 PM' (inferred)."""
    raw = raw.strip()
    if "AM" in raw or "PM" in raw:
        parts = raw.split()
        hm = parts[0].split(":")
        return f"{int(hm[0])}:{hm[1]} {parts[1]}"
    hm = raw.split(":")
    return f"{int(hm[0])}:{hm[1]} {meridiem}"


def fmt_iqamah(raw, meridiem):
    """'6:20' -> '6:20 AM' using the inferred meridiem."""
    hm = raw.strip().split(":")
    return f"{int(hm[0])}:{hm[1]} {meridiem}"


def get_prayer():
    iok = http_json("https://isalaah.com/loc/ca/schedule.php?p=clk&loc=iok")
    chess = http_json("https://isalaah.com/loc/ca/schedule.php?p=clk&loc=chess")
    rows = []
    for idx, (name, tkey, ikey, mer) in enumerate(PRAYERS):
        if name == "Dhuhr":  # Sunrise sits between Fajr and Dhuhr
            rows.append({
                "name": "Sunrise",
                "azan": fmt_iqamah(iok["s"], "AM"),
                "iqamah": None,
                "chess_iqamah": None,
            })
        rows.append({
            "name": name,
            "azan": fmt_azan(iok[tkey], mer),
            "iqamah": fmt_iqamah(iok[ikey], mer),
            "chess_iqamah": fmt_iqamah(chess[ikey], mer),
        })
    return {"feed_date": iok.get("nf"), "today": rows}


# --------------------------------------------------------------- weather ---

def wmo_label(code):
    if code == 0:
        return "Clear"
    if code in (1, 2):
        return "Partly cloudy"
    if code == 3:
        return "Overcast"
    if code in (45, 48):
        return "Fog"
    if 51 <= code <= 67:
        return "Rain"
    if 71 <= code <= 77:
        return "Snow"
    if 80 <= code <= 82:
        return "Showers"
    if code >= 95:
        return "Thunderstorm"
    return "—"


def get_weather():
    # Full forecast object — the same shape the page builds from its own
    # live fetch — so the snapshot fallback renders the weather view
    # complete (hours/days/tiles), not just the home-screen header.
    url = (
        "https://api.open-meteo.com/v1/forecast?latitude=34.0009&longitude=-117.8291"
        "&current=temperature_2m,weather_code,apparent_temperature,"
        "relative_humidity_2m,wind_speed_10m,wind_direction_10m,wind_gusts_10m"
        "&hourly=temperature_2m,weather_code,precipitation_probability,"
        "visibility,is_day"
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,"
        "sunset,uv_index_max,precipitation_probability_max,precipitation_sum"
        "&temperature_unit=fahrenheit&wind_speed_unit=mph"
        "&timezone=America%2FLos_Angeles&forecast_days=10"
    )
    d = http_json(url)
    cur, hourly, daily = d["current"], d["hourly"], d["daily"]
    now = datetime.now(LOCAL_TZ)
    hi0 = 0
    for i, t in enumerate(hourly["time"]):
        dt = datetime.fromisoformat(t)
        if dt.date() == now.date() and dt.hour == now.hour:
            hi0 = i
            break
    pop_h = hourly.get("precipitation_probability")
    is_day = hourly.get("is_day")
    hours = []
    for i in range(hi0, min(hi0 + 25, len(hourly["time"]))):
        dt = datetime.fromisoformat(hourly["time"][i])
        h12 = dt.hour % 12 or 12
        hours.append({
            "t": "Now" if i == hi0 else
                 f"{h12}{'PM' if dt.hour >= 12 else 'AM'}",
            "f": round(hourly["temperature_2m"][i]),
            "code": hourly["weather_code"][i],
            "night": (not is_day[i]) if is_day else False,
            "pop": pop_h[i] if pop_h else None,
        })
    pop_d = daily.get("precipitation_probability_max")
    days = []
    for i, t in enumerate(daily["time"]):
        dt = datetime.fromisoformat(t + "T12:00:00")
        days.append({
            "d": "Today" if i == 0 else dt.strftime("%a"),
            "code": daily["weather_code"][i],
            "label": wmo_label(daily["weather_code"][i]),
            "hi": round(daily["temperature_2m_max"][i]),
            "lo": round(daily["temperature_2m_min"][i]),
            "pop": pop_d[i] if pop_d else None,
        })

    def fmt_t(s):
        dt = datetime.fromisoformat(s)
        h12 = dt.hour % 12 or 12
        return f"{h12}:{dt.minute:02d} {'PM' if dt.hour >= 12 else 'AM'}"

    vis = hourly.get("visibility")
    return {
        "now_f": round(cur["temperature_2m"]),
        "label": wmo_label(cur["weather_code"]),
        "code": cur["weather_code"],
        "feels": round(cur["apparent_temperature"]),
        "hum": round(cur["relative_humidity_2m"]),
        "wind": round(cur["wind_speed_10m"]),
        "windDir": round(cur["wind_direction_10m"]),
        "gust": round(cur["wind_gusts_10m"]),
        "visMi": round(vis[hi0] / 1609.34)
                 if vis and vis[hi0] is not None else None,
        "uv": round(daily["uv_index_max"][0] * 10) / 10
              if daily.get("uv_index_max") else None,
        "sunrise": fmt_t(daily["sunrise"][0]),
        "sunset": fmt_t(daily["sunset"][0]),
        "precipIn": round(daily["precipitation_sum"][0] / 25.4 * 100) / 100
                    if daily.get("precipitation_sum") else None,
        "hi": round(daily["temperature_2m_max"][0]),
        "lo": round(daily["temperature_2m_min"][0]),
        "hours": hours,
        "days": days,
    }


# --------------------------------------------------------------- grocery ---

def get_grocery():
    params = json.dumps({
        "spreadsheetId": "102Y_rnhGDcoMy6uIry_8aLPsILLWPQcoobf4vETAg3M",
        "range": "Grocery list!A1:B40",
    })
    d = run_cli([GWS, "sheets", "spreadsheets", "values", "get",
                 "--params", params])
    rows = d.get("values") or d.get("result", {}).get("values") or []
    now, later = [], []
    for row in rows:
        a = (row[0].strip() if len(row) > 0 and row[0] else "")
        b = (row[1].strip() if len(row) > 1 and row[1] else "")
        if a:
            now.append(a)
        if b:
            later.append(b)
    return {"now": now, "later": later}


# ---------------------------------------------------------------- agenda ---

def fmt_event_time(ev):
    start = ev.get("start")
    if isinstance(start, dict):
        dt_raw = start.get("dateTime")
        if not dt_raw:
            return "All day"
    elif isinstance(start, str):
        dt_raw = start
    else:
        return "All day"
    try:
        dt = datetime.fromisoformat(dt_raw.replace("Z", "+00:00"))
        return dt.astimezone().strftime("%-I:%M %p")
    except ValueError:
        return "All day"


def get_agenda():
    d = run_cli([GWS, "calendar", "+agenda", "--today", "--format", "json"])
    if isinstance(d, list):
        events = d
    elif isinstance(d, dict):
        events = (d.get("events") or d.get("items")
                  or d.get("result", {}).get("events") or [])
    else:
        events = []
    out = []
    for ev in events:
        if not isinstance(ev, dict):
            continue
        title = ev.get("summary") or ev.get("title") or "(untitled)"
        out.append({"time": fmt_event_time(ev), "title": title})
    return out


# ------------------------------------------------------- calendar weeks ---

def cal_list_params(cal_id, t_min, t_max, page_token=None):
    p = {"calendarId": cal_id, "timeMin": t_min, "timeMax": t_max,
         "singleEvents": True, "orderBy": "startTime", "maxResults": 250}
    if page_token:
        p["pageToken"] = page_token
    return json.dumps(p)


def get_calweeks():
    now = datetime.now(LOCAL_TZ)
    day0 = now.replace(hour=0, minute=0, second=0, microsecond=0)
    t_min = (day0 - timedelta(days=35)).isoformat()
    t_max = (day0 + timedelta(days=36)).isoformat()

    cals = run_cli([GWS, "calendar", "calendarList", "list",
                    "--params", '{"minAccessRole": "reader"}'])
    items = (cals.get("items") or cals.get("result", {}).get("items") or [])
    out = []
    for cal in items:
        if cal.get("accessRole") not in ("owner", "writer", "reader"):
            continue
        cal_id = cal.get("id")
        if not cal_id:
            continue
        token = None
        for _ in range(4):  # follow pagination a few pages deep
            d = run_cli([GWS, "calendar", "events", "list",
                         "--params", cal_list_params(cal_id, t_min, t_max, token)])
            evs = d.get("items") or d.get("result", {}).get("items") or []
            for ev in evs:
                if not isinstance(ev, dict) or ev.get("status") == "cancelled":
                    continue
                st, en = ev.get("start") or {}, ev.get("end") or {}
                title = ev.get("summary") or "(no title)"
                loc = ev.get("location") or ""
                if st.get("dateTime") and en.get("dateTime"):
                    out.append({"start": st["dateTime"], "end": en["dateTime"],
                                "title": title, "allday": False, "location": loc,
                                "cal": cal.get("summary") or ""})
                elif st.get("date") and en.get("date"):
                    out.append({"start": st["date"], "end": en["date"],
                                "title": title, "allday": True, "location": loc,
                                "cal": cal.get("summary") or ""})
            token = d.get("nextPageToken") or d.get("result", {}).get("nextPageToken")
            if not token:
                break
    out.sort(key=lambda e: e["start"])
    return out


# ------------------------------------------------------------ queue drain ---

PORTAL_URL = "http://192.168.1.117:8321"
SSH_TARGET = "Sameer@100.103.213.69"
SSH_KEY = "/home/hatch/.ssh/tv-project-mac"
SSH_PROXY = "python3 /home/hatch/workspace/tv-project/proxy-connect.py %h %p"


def portal_curl(remote_cmd, timeout=40):
    """Run a curl against the Portal from the Mac (the VM has no LAN
    route to it; the Mac is on its network)."""
    proc = subprocess.run(
        ["ssh", "-i", SSH_KEY, "-o", f"ProxyCommand={SSH_PROXY}",
         "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=15",
         SSH_TARGET, remote_cmd],
        capture_output=True, text=True, timeout=timeout)
    if proc.returncode != 0:
        raise RuntimeError(f"portal curl exit {proc.returncode}: {proc.stderr.strip()[:200]}")
    return proc.stdout


def drain_queue():
    raw = portal_curl(f"curl -s --max-time 15 {PORTAL_URL}/cal-queue.json")
    try:
        queue = json.loads(raw)
    except ValueError:
        queue = []  # 404 page or empty body: nothing queued
    if not isinstance(queue, list) or not queue:
        return
    done = []
    for entry in queue:
        if not isinstance(entry, dict) or not entry.get("id"):
            continue
        title = (entry.get("title") or "").strip()
        start, end = entry.get("start") or "", entry.get("end") or ""
        if not title or not start or not end:
            continue
        if entry.get("allday"):
            body = {"summary": title,
                    "start": {"date": start}, "end": {"date": end}}
        else:
            body = {"summary": title,
                    "start": {"dateTime": start, "timeZone": "America/Los_Angeles"},
                    "end": {"dateTime": end, "timeZone": "America/Los_Angeles"}}
        if entry.get("location"):
            body["location"] = entry["location"]
        try:
            run_cli([GWS, "calendar", "events", "insert",
                     "--params", '{"calendarId": "primary"}',
                     "--json", json.dumps(body)])
            done.append(entry["id"])
        except Exception as exc:  # leave it queued; retry next run
            log_err("drain insert", exc)
    if done:
        payload = json.dumps({"ids": done})
        portal_curl("curl -s --max-time 15 -X POST "
                    "-H 'Content-Type: application/json' "
                    f"--data-binary {shlex.quote(payload)} "
                    f"{PORTAL_URL}/api/calendar/dequeue")


# ------------------------------------------------------------------ main ---

def main():
    try:
        drain_queue()
    except Exception as exc:  # a dead Portal/LAN must not kill the file
        log_err("drain", exc)

    data = {}
    now = datetime.now(LOCAL_TZ)
    data["generated_at"] = now.isoformat(timespec="seconds")
    data["date_label"] = now.strftime("%A, %B %-d")

    for key, fn in (("prayer", get_prayer), ("weather", get_weather),
                    ("grocery", get_grocery), ("agenda", get_agenda),
                    ("calweeks", get_calweeks)):
        try:
            data[key] = fn()
        except Exception as exc:  # one dead source must not kill the file
            log_err(key, exc)
            data[key] = None

    if WORD_FILE.exists():
        try:
            data["word"] = json.loads(WORD_FILE.read_text())
        except Exception as exc:
            log_err("word", exc)

    OUT.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
