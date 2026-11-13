#!/usr/bin/env python3
"""Auto-attach to OPW2 over wireless adb while the phone VPN is temporarily off.

Polls the watch IP; as soon as it is reachable:
  pair -> scan for adbd port -> connect -> install APK -> grant permissions
  -> run probes -> pull files -> done.

Usage:
  python3 tools/attach.py --ip 10.177.43.243 --pair-port 39441 --code 982771 \
      --apk /sdcard/Download/AndCode/smart-alarm/opw2-probe.apk \
      --out /sdcard/Download/AndCode/smart-alarm/probe-data
"""
import argparse
import asyncio
import os
import socket
import subprocess
import sys
import time
from datetime import datetime

PKG = "io.github.smartalarm"
REMOTE_DIR = f"/sdcard/Android/data/{PKG}/files"

def log(msg):
    line = f"[{datetime.now().strftime('%H:%M:%S')}] {msg}"
    print(line, flush=True)

def tcp_ok(ip, port, timeout=2.0):
    s = socket.socket()
    s.settimeout(timeout)
    try:
        s.connect((ip, port))
        return True
    except Exception:
        return False
    finally:
        s.close()

async def scan_port(ip, port, timeout=0.35):
    try:
        fut = asyncio.open_connection(ip, port)
        reader, writer = await asyncio.wait_for(fut, timeout=timeout)
        writer.close()
        try:
            await writer.wait_closed()
        except Exception:
            pass
        return port
    except Exception:
        return None

async def scan(ip, ports, workers=800):
    sem = asyncio.Semaphore(workers)
    found = []

    async def one(p):
        async with sem:
            r = await scan_port(ip, p)
            if r:
                found.append(r)

    await asyncio.gather(*(one(p) for p in ports))
    return sorted(found)

def adb(*args, timeout=60):
    cmd = ["adb"] + list(args)
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return r.returncode, (r.stdout or "") + (r.stderr or "")
    except subprocess.TimeoutExpired:
        return 124, "timeout"

def adb_devices():
    rc, out = adb("devices", timeout=15)
    return out

def find_serial():
    rc, out = adb_devices()
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            return parts[0]
    return None

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ip", required=True)
    ap.add_argument("--pair-port", type=int, required=True)
    ap.add_argument("--code", required=True)
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--deadline-min", type=int, default=20)
    args = ap.parse_args()

    serial = None
    paired = False
    deadline = time.time() + args.deadline_min * 60
    log(f"waiting for {args.ip} to become reachable (VPN off), up to {args.deadline_min} min")

    while time.time() < deadline:
        if not tcp_ok(args.ip, args.pair_port):
            time.sleep(3)
            continue

        log("watch reachable")
        if not paired:
            rc, out = adb("pair", f"{args.ip}:{args.pair_port}", args.code, timeout=40)
            log(f"pair rc={rc}: {out.strip()[:300]}")
            if "Successfully paired" in out or "already paired" in out.lower():
                paired = True
            elif "wrong" in out.lower() or "failed" in out.lower():
                log("pair failed, refreshing code needed; retrying once")
                time.sleep(2)

        if paired and not serial:
            log("scanning adbd port")
            ports = list(range(30000, 61000))
            found = asyncio.run(scan(args.ip, ports))
            log(f"open ports: {found[:20]}")
            for p in found:
                rc, out = adb("connect", f"{args.ip}:{p}", timeout=15)
                if "connected" in out.lower():
                    log(f"connect {p}: {out.strip()}")
                if find_serial():
                    serial = find_serial()
                    break
            if not serial:
                log("no adb port found yet; retrying")
                time.sleep(5)
                continue

        if serial:
            break
        time.sleep(3)

    if not serial:
        log("FAILED: no adb device")
        return 1

    log(f"device online: {serial}")
    rc, out = adb("-s", serial, "shell", "getprop", "ro.product.model")
    log(f"model: {out.strip()}")

    log("installing apk")
    rc, out = adb("-s", serial, "install", "-r", "-g", args.apk, timeout=300)
    log(f"install rc={rc}: {out.strip()[:400]}")

    for perm in (
        "android.permission.BODY_SENSORS",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.BODY_SENSORS_BACKGROUND",
        "android.permission.VIBRATE",
    ):
        rc, out = adb("-s", serial, "shell", "pm", "grant", PKG, perm, timeout=30)
        log(f"grant {perm}: {out.strip()[:120]}")

    adb("-s", serial, "shell", "am", "start", "-n", f"{PKG}/.ProbeActivity", timeout=30)
    time.sleep(2)

    log("running probes")
    for action, wait in (
        ("RUN_SENSORS", 4),
        ("RUN_HS", 12),
        ("RUN_HC", 8),
        ("RUN_PPG", 90),
        ("RUN_RAW", 75),
    ):
        rc, out = adb(
            "-s", serial, "shell", "am", "broadcast",
            "-a", f"{PKG}.{action}", "-n", f"{PKG}/.TestReceiver",
            timeout=20,
        )
        log(f"{action}: {out.strip()[:120]}")
        time.sleep(wait)

    adb("-s", serial, "shell", "am", "broadcast", "-a", f"{PKG}.DUMP_LOG",
        "-n", f"{PKG}/.TestReceiver", timeout=20)

    os.makedirs(args.out, exist_ok=True)
    log("pulling files")
    rc, out = adb("-s", serial, "pull", REMOTE_DIR + "/.", args.out, timeout=180)
    log(f"pull rc={rc}: {out.strip()[:300]}")

    with open(os.path.join(args.out, "adb_devices.txt"), "w") as f:
        f.write(adb_devices())
    log("DONE")
    return 0

if __name__ == "__main__":
    sys.exit(main())
