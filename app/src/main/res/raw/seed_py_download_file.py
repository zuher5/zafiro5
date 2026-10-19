#!/usr/bin/env python3
"""Download tool: download a file from a URL to a local directory.

The default directory is the app's private downloads subdirectory (injected by the host;
this script does not choose it), which is process-private, writable, and needs no external
storage permission. Cleanup and renaming after the download are left to the caller.

Usage (Termux or any environment with python3):
    python3 download_file.py https://example.com/file.png
    python3 download_file.py https://example.com/file.png --filename out.png

Exit codes: 0 success; 1 download failed.

Can also be registered as a Zafiro python tool: main(url, filename="", path="").
"""
import argparse
import json
import os
import sys
import urllib.request
import uuid
from urllib.parse import urlparse

UA = "Mozilla/5.0 (Linux; Android 14)"
DOWNLOAD_TIMEOUT = 120

# Replaced with an absolute path at seed time (files/downloads/py_download_file);
# when the placeholder is not replaced, the script's current directory is used, so it
# still works when run directly from the command line.
DEFAULT_DOWNLOAD_DIR = "__DEFAULT_DOWNLOAD_DIR__"


def resolve_dest(url, filename, download_dir):
    """Return the final path: explicit filename > extracted from URL > UUID."""
    if filename:
        return os.path.join(download_dir, filename)
    name = os.path.basename(urlparse(url).path)
    if not name or "." not in name:
        name = str(uuid.uuid4())
    return os.path.join(download_dir, name)


def main(url, filename="", path=""):
    try:
        download_dir = path or DEFAULT_DOWNLOAD_DIR
        os.makedirs(download_dir, exist_ok=True)
        dest = resolve_dest(url, filename, download_dir)

        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=DOWNLOAD_TIMEOUT) as resp, open(dest, "wb") as f:
            while True:
                chunk = resp.read(8192)
                if not chunk:
                    break
                f.write(chunk)

        size = os.path.getsize(dest)
        print(json.dumps({"ok": True, "path": dest, "size": size}))
    except Exception as e:
        print(json.dumps({"ok": False, "error": str(e)}))


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("url", help="Target file URL")
    ap.add_argument("--filename", default="", help="Desired filename (extracted from the URL if omitted)")
    ap.add_argument("--path", default="", help="Download directory (defaults to the current directory; the host injects the private directory)")
    args = ap.parse_args()
    main(args.url, filename=args.filename, path=args.path)
