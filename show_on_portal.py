#!/usr/bin/env python3
"""Push a visual card onto the Portal dashboard (DashServer /api/show).

The card (title, optional subtitle, optional image, and a URL) pops
over the dashboard on the Portal; tapping Open hands the URL to
Chrome on the device via the page's portal://open intercept, and ✕
(or opening) clears it server-side.

Usage:
  python3 show_on_portal.py --title "Fluffy Pancakes" \
      --url "https://www.youtube.com/watch?v=..." \
      [--subtitle "Ready in 15 minutes"] \
      [--image "https://i.ytimg.com/vi/.../hqdefault.jpg"]
  python3 show_on_portal.py --clear

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


def main():
    ap = argparse.ArgumentParser(description="Show a card on the Portal dashboard")
    ap.add_argument("--title")
    ap.add_argument("--url")
    ap.add_argument("--subtitle")
    ap.add_argument("--image")
    ap.add_argument("--clear", action="store_true", help="clear the current card")
    args = ap.parse_args()

    host, port, token = load_config()

    if args.clear:
        status, body = post(host, port, "/api/show/clear")
        print(f"clear: HTTP {status} {body}")
        return 0

    if not args.title or not args.url:
        ap.error("--title and --url are required (or use --clear)")
    payload = {"title": args.title, "url": args.url}
    if args.subtitle:
        payload["subtitle"] = args.subtitle
    if args.image:
        payload["image"] = args.image
    status, body = post(host, port, "/api/show", token=token, payload=payload)
    print(f"show: HTTP {status} {body}")
    return 0 if status == 200 else 1


if __name__ == "__main__":
    sys.exit(main())
