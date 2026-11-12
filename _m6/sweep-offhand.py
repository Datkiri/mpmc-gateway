#!/usr/bin/env python3
"""Sweep the .13-exp control knobs against a single OFFHAND/ARMOR creative
click to isolate Bedrock ITEM_STACK_REQUEST status 50 (FailedToValidateDstSlot).

The .13-exp jar reads -Dmpmc.req.control=<path> before every
SET_CREATIVE_MODE_SLOT, so one gateway process can exercise many action
chains without a rebuild. Pure CLI, one live BDS, no files deleted.
"""
import json
import os
import re
import shutil
import socket
import subprocess
import time

ROOT = os.path.dirname(os.path.abspath(__file__))
GW = os.path.join(ROOT, "gw-data")
CLIENT = os.path.join(os.path.dirname(ROOT), "_m5", "client")
JAVA = r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe"
NODE = r"C:\Users\Acer\.workbuddy-ai\binaries\node\versions\22.22.2-6\node.exe"
EXP_JAR = os.path.join(GW, "gateway-exp", "ViaProxy-3.4.14.jar")
DIR = os.path.join(GW, "sweep-offhand")
PORT = 25579
CONTROL = os.path.join(DIR, "req-control.txt")
LOG = os.path.join(DIR, "vp.log")
CASE_DIR = os.path.join(CLIENT, "sweep-cases")
os.makedirs(CASE_DIR, exist_ok=True)

CASES = {
    "offhand": [{"tag": "offhand", "slot": 45, "id": 588, "c": 64, "pair": False, "comp": False}],
    "armor": [{"tag": "armor", "slot": 5, "id": 396, "c": 1, "pair": False, "comp": False}],
    "normal": [{"tag": "normal", "slot": 36, "id": 396, "c": 64, "pair": False, "comp": False}],
}

CONFIGS = [
    ("offhand", "mode=full,reset=1"),
    ("offhand", "mode=place,reset=1"),
    ("offhand", "mode=cc-results-take,reset=1"),
    ("offhand", "mode=cc-only,reset=1"),
    ("offhand", "mode=results-only,reset=1"),
    ("offhand", "mode=create,reset=1"),
    ("armor", "mode=full,reset=1"),
    ("armor", "mode=place,reset=1"),
    # Control group: identical action chains on a plain inventory slot.
    ("normal", "mode=full,reset=1"),
    ("normal", "mode=place,reset=1"),
    ("normal", "mode=cc-results-take,reset=1"),
    ("normal", "mode=cc-only,reset=1"),
    ("normal", "mode=results-only,reset=1"),
    ("normal", "mode=create,reset=1"),
]


def wait_port(port, timeout=90):
    end = time.time() + timeout
    while time.time() < end:
        try:
            with socket.create_connection(("127.0.0.1", port), 0.5):
                return True
        except OSError:
            time.sleep(0.5)
    return False


def clean(line):
    return re.sub(r"\x1b\[[0-9;]*m", "", line)


def main():
    os.makedirs(DIR, exist_ok=True)
    shutil.copy2(EXP_JAR, os.path.join(DIR, "ViaProxy-3.4.14.jar"))
    for name, cases in CASES.items():
        with open(os.path.join(CASE_DIR, name + ".json"), "w") as f:
            json.dump(cases, f)
    open(CONTROL, "w").write("mode=full,reset=1")

    logf = open(LOG, "w", encoding="utf-8", errors="replace")
    cmd = [JAVA, "-Dfile.encoding=UTF-8",
           "-Dmpmc.req.control=" + CONTROL,
           "-jar", "ViaProxy-3.4.14.jar", "cli",
           "--bind-address", "127.0.0.1:%d" % PORT,
           "--target-address", "127.0.0.1:19132",
           "--target-version", "Bedrock 1.26.51",
           "--auth-method", "NONE",
           "--ignore-protocol-translation-errors", "true"]
    p = subprocess.Popen(cmd, cwd=DIR, stdout=logf, stderr=subprocess.STDOUT)
    print("exp gateway pid=%d port=%d" % (p.pid, PORT), flush=True)
    results = []
    try:
        if not wait_port(PORT):
            print("[FATAL] port did not open")
            return 2
        for i, (case, control) in enumerate(CONFIGS):
            with open(CONTROL, "w") as f:
                f.write(control)
            marker = os.path.getsize(LOG)
            env = dict(os.environ)
            env.update({
                "MC_PORT": str(PORT),
                "MC_USER": "SW%d" % i,
                "MC_VERSION": "1.21.1",
                "MC_CASES": os.path.join(CASE_DIR, case + ".json"),
                "MC_GAP_MS": "100",
                "MC_TAIL_MS": "4000",
            })
            subprocess.run([NODE, "gui-repro.js"], cwd=CLIENT, env=env,
                           stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, timeout=60)
            time.sleep(1.2)
            with open(LOG, "r", encoding="utf-8", errors="replace") as f:
                f.seek(marker)
                chunk = clean(f.read())
            status = re.findall(r"rejected item stack request (-\d+) \(status (\d+)", chunk)
            accepted = len(re.findall(r"accepted item stack request", chunk))
            creative = len(re.findall(r"Creative slot", chunk))
            results.append({
                "case": case, "control": control, "creative": creative,
                "accepted": accepted, "rejected": status,
            })
            print("%-8s %-38s creative=%d accepted=%d rejected=%s" %
                  (case, control, creative, accepted, status), flush=True)
        out = os.path.join(GW, "sweep-offhand-result.json")
        with open(out, "w") as f:
            json.dump(results, f, ensure_ascii=False, indent=2)
        print("\nwritten:", out)
        return 0
    finally:
        if p.poll() is None:
            p.terminate()
            try:
                p.wait(timeout=15)
            except subprocess.TimeoutExpired:
                p.kill()
        logf.close()
        print("[cleanup] sweep gateway stopped", flush=True)


if __name__ == "__main__":
    raise SystemExit(main())
