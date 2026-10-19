#!/usr/bin/env python3
"""APK installer: given a direct APK URL, download and install it on this Android device via su.

Non-force by default: if the target app is already installed, the install is skipped.
No separate package check is done -- this relies on `pm install` without -r reporting
INSTALL_FAILED_ALREADY_EXISTS when the package exists, which is treated as "already
installed, skipping". With --force, -r is added to overwrite the install.

Usage (Termux or any environment with su access):
    python3 install_apk.py https://example.com/app.apk
    python3 install_apk.py --force https://example.com/app.apk

Exit codes: 0 installed; 1 install failed; 2 su unavailable/not granted; 3 already installed, skipped.

Can also be registered as a Zafiro python tool: main(url, force=False).
"""
import argparse
import os
import shlex
import shutil
import subprocess
import sys
import urllib.request
import uuid

INSTALL_TIMEOUT = 300
UA = "Mozilla/5.0 (Linux; Android 14)"

# Replaced with an absolute path at seed time (files/downloads/py_install_apk);
# when the placeholder is not replaced, the literal value is used as the directory.
DEFAULT_DOWNLOAD_DIR = "__DEFAULT_DOWNLOAD_DIR__"


def su_run(args, timeout=INSTALL_TIMEOUT):
    """Run a command as root; raises FileNotFoundError when su is missing."""
    cmd = ["su", "-c", " ".join(shlex.quote(a) for a in args)]
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def download(url, dest):
    print(f"[1/2] downloading {url}")
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=120) as resp, open(dest, "wb") as f:
        shutil.copyfileobj(resp, f)
    print(f"      saved {dest} ({os.path.getsize(dest)} bytes)")


def install(apk_path, force):
    cmd = ["pm", "install"]
    if force:
        cmd.append("-r")
    cmd.append(apk_path)
    return su_run(cmd)


def main(url, force=False, path=""):
    try:
        probe = su_run(["id"], timeout=10)
    except FileNotFoundError:
        print("su not found: root is required (grant it in Magisk / KernelSU and retry)", file=sys.stderr)
        sys.exit(2)
    if probe.returncode != 0:
        print("su not granted or unavailable", file=sys.stderr)
        sys.exit(2)

    download_dir = path or DEFAULT_DOWNLOAD_DIR
    os.makedirs(download_dir, exist_ok=True)
    apk_path = os.path.join(download_dir, f"{uuid.uuid4()}.apk")
    try:
        download(url, apk_path)
        print(f"[2/2] installing via su ({'force' if force else 'non-force'})")
        r = install(apk_path, force)
        out = (r.stdout + r.stderr).strip()
        if r.returncode != 0:
            if "INSTALL_FAILED_ALREADY_EXISTS" in out:
                print("already installed, skipping (add --force to overwrite)")
                sys.exit(3)
            print(f"install failed: {out or 'unknown error'}", file=sys.stderr)
            sys.exit(1)
        print("installed")
    finally:
        try:
            os.unlink(apk_path)
        except OSError:
            pass


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("url", help="APK direct URL")
    ap.add_argument("--force", action="store_true", help="Overwrite the install (skip if already installed by default)")
    ap.add_argument("--path", default="", help="Download directory for the APK (defaults to the private directory)")
    args = ap.parse_args()
    main(args.url, force=args.force, path=args.path)
