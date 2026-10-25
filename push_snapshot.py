#!/usr/bin/env python3
"""Push the dashboard snapshot to the Portal over Tailscale.

Flow: rebuild data.json (build_data.py), then PUT data.json and the two
word-of-the-day audio files to the Portal's DashServer upload endpoint
through the Tailscale proxy (the VM is a tailnet client; it cannot host).

Config lives in portal-push.json next to this script:
    {"portal_host": "<portal tailnet ip or name>", "port": 8321}
The push token is read from .dash-token in the same folder.

Until portal-push.json exists (Tailscale setup not done yet), this is a
silent no-op so the scheduler can run it harmlessly.
"""
import json
import os
import subprocess
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
FILES = ["data.json", "audio/wotd-en.mp3", "audio/wotd-ur.mp3", "index.html"]


def main():
    conf_path = HERE / "portal-push.json"
    if not conf_path.exists():
        print("push_snapshot: not configured yet (no portal-push.json); skipping")
        return 0
    conf = json.loads(conf_path.read_text())
    host = conf["portal_host"]
    port = conf.get("port", 8321)
    token = (HERE / ".dash-token").read_text().strip()

    r = subprocess.run([sys.executable, str(HERE / "build_data.py")],
                       capture_output=True, text=True, timeout=120)
    if r.returncode != 0:
        print(f"push_snapshot: build_data.py failed: {r.stderr.strip()[:300]}",
              file=sys.stderr)
        return 1

    # Route through the Tailscale tunnel proxy (runtime proxy, port 3130).
    https_proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy")
    if https_proxy:
        proxy = https_proxy.rsplit(":", 1)[0] + ":3130"
        opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
    else:
        opener = urllib.request.build_opener()

    for rel in FILES:
        data = (HERE / rel).read_bytes()
        req = urllib.request.Request(
            f"http://{host}:{port}/upload/{rel}", data=data, method="PUT",
            headers={"Authorization": f"Bearer {token}",
                     "Content-Type": "application/octet-stream"})
        try:
            with opener.open(req, timeout=30) as resp:
                if resp.status != 200:
                    print(f"push_snapshot: PUT {rel} -> HTTP {resp.status}",
                          file=sys.stderr)
                    return 1
        except Exception as e:  # noqa: BLE001
            print(f"push_snapshot: PUT {rel} failed: {e}", file=sys.stderr)
            return 1
    print(f"push_snapshot: pushed {len(FILES)} files to {host}:{port}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
