#!/usr/bin/env python3
"""Push a visual card onto the Portal dashboard (DashServer /api/show).

The content takes over the dashboard's full Show page on the
Portal (v3: a full view, not a pop-up). It can be a simple link
card (title + URL) or rich content whose sections — ingredients,
steps — are laid out as full-page columns (v2 sections); with
sections the URL is optional and just rides along as the Open
link. Tapping Open hands the URL to Chrome on the device via the
page's portal://open intercept and the page stays underneath;
only the page's Done (✕) clears it server-side, so the content
survives being opened.

Usage:
  python3 show_on_portal.py --title "Fluffy Pancakes" \
      --url "https://www.youtube.com/watch?v=..." \
      [--subtitle "Ready in 15 minutes"] \
      [--image "https://i.ytimg.com/vi/.../hqdefault.jpg"] \
      [--sections recipe.txt]
  python3 show_on_portal.py --clear

Sections file format: a line starting with "## " begins a section;
every other non-empty line is a content line in that section.

Connection + secret come from this directory: portal-push.json
(portal_host/port) and .dash-token (the DashServer bearer token) —
neither is ever committed. Requests ride the Tailscale tunnel proxy
(HTTPS_PROXY with the port swapped to 3130), same as push_snapshot.py.
"""
import argparse
import json
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))


def load_config():
    with open(os.path.join(HERE, "portal-push.json")) as f:
        cfg = json.load(f)
    with open(os.path.join(HERE, ".dash-token")) as f:
        token = f.read().strip()
    return cfg["portal_host"], int(cfg.get("port", 8321)), token


def post(host, port, path, token=None, payload=None):
    proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy") or ""
    if ":" in proxy:
        proxy = proxy.rsplit(":", 1)[0] + ":3130"
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({"http": proxy} if proxy else {}))
    url = f"http://{host}:{port}{path}"
    data = json.dumps(payload).encode() if payload is not None else b""
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with opener.open(req, timeout=20) as resp:
        return resp.status, resp.read().decode()


def parse_sections(path):
    """Sections text file -> [{"heading": ..., "lines": [...]}].

    A line starting with "## " begins a section; other non-empty
    lines are content lines. Lines before the first heading collect
    into a heading-less section."""
    sections = []
    current = None
    with open(path, encoding="utf-8") as f:
        for raw in f:
            line = raw.strip()
            if line.startswith("## "):
                current = {"heading": line[3:].strip(), "lines": []}
                sections.append(current)
            elif line:
                if current is None:
                    current = {"heading": "", "lines": []}
                    sections.append(current)
                current["lines"].append(line)
    return [s for s in sections if s["lines"]]


def main():
    ap = argparse.ArgumentParser(description="Show a card on the Portal dashboard")
    ap.add_argument("--title")
    ap.add_argument("--url")
    ap.add_argument("--subtitle")
    ap.add_argument("--image")
    ap.add_argument("--sections", help="text file: '## Heading' starts a section")
    ap.add_argument("--clear", action="store_true", help="clear the current card")
    args = ap.parse_args()

    host, port, token = load_config()

    if args.clear:
        status, body = post(host, port, "/api/show/clear")
        print(f"clear: HTTP {status} {body}")
        return 0

    if not args.title:
        ap.error("--title is required (or use --clear)")
    sections = parse_sections(args.sections) if args.sections else None
    if not args.url and not sections:
        ap.error("--url is required unless --sections is given")
    payload = {"title": args.title}
    if args.url:
        payload["url"] = args.url
    if args.subtitle:
        payload["subtitle"] = args.subtitle
    if args.image:
        payload["image"] = args.image
    if sections:
        payload["sections"] = sections
    status, body = post(host, port, "/api/show", token=token, payload=payload)
    print(f"show: HTTP {status} {body}")
    return 0 if status == 200 else 1


if __name__ == "__main__":
    sys.exit(main())
