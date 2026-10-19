import shlex
import subprocess
import sys


def su_run(args, timeout=15):
    """Run a command as root; raises FileNotFoundError when su is missing."""
    cmd = ["su", "-c", " ".join(shlex.quote(a) for a in args)]
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def main():
    '''Launch WeChat.'''
    # Starting the activity without root raises SecurityException: Permission Denied,
    # so run it through su; the first call shows the su grant dialog.
    try:
        result = su_run(["am", "start", "-n", "com.tencent.mm/com.tencent.mm.ui.LauncherUI"])
    except FileNotFoundError:
        print("su not found: root is required (grant it in Magisk / KernelSU and retry)", file=sys.stderr)
        raise SystemExit(2)
    if result.returncode != 0:
        print((result.stderr or result.stdout).strip())
        raise SystemExit(1)
    print("WeChat launched.")
