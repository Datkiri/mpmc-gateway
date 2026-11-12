#!/usr/bin/env python3
"""Test whether BDS accepts a survival-style ITEM_STACK_REQUEST (pure Take,
no craft action) moving an EXISTING inventory stack into OffhandContainer.

Sequence per pass, one Java 1.21.1 CLI client on one live BDS:
  1. set_creative_slot 36 (shield) -> normal CraftCreative chain into Bedrock
     inventory slot 0; BDS assigns a real stack net id.
  2. set_creative_slot 45 (shield) -> with pureMoveSlot=45 the gateway emits a
     single Take from inventory slot 0 (net id read from the tracker) into
     OffhandContainer/0, with NO craft action.

Control pass omits pureMoveSlot so step 2 is the ordinary craft chain, which
BDS rejects with status 55.
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
NEW_JAR = os.path.join(ROOT, "gateway-build", "ViaProxy", "build", "libs", "ViaProxy-3.4.14.jar")
DIR = os.path.join(GW, "gateway-14pure")
PORT = 25586
CONTROL = os.path.join(DIR, "req-control.txt")
LOG = os.path.join(DIR, "vp.log")
CASES = os.path.join(DIR, "cases.json")

CASES_DATA = [
    {"tag": "seed-inventory", "slot": 36, "id": 1162, "c": 1},
    {"tag": "offhand-move", "slot": 45, "id": 1162, "c": 1},
]

PASSES = [
    ("A-pure-move", "equip=request,pureMoveSlot=45,pmSrc=0"),
    ("B-control-full", "equip=request"),
    ("C-pure-move-armor", "equip=request,pureMoveSlot=5,pmSrc=0"),
]


def clean(s):
    return re.sub(r"\x1b\[[0-9;]*m", "", s)


def wait_port(port, timeout=90):
    end = time.time() + timeout
    while time.time() < end:
        try:
            with socket.create_connection(("127.0.0.1", port), 0.5):
                return True
        except OSError:
            time.sleep(0.5)
    return False


def main():
    os.makedirs(DIR, exist_ok=True)
    shutil.copy2(NEW_JAR, os.path.join(DIR, "ViaProxy-3.4.14.jar"))
    with open(CASES, "w") as f:
        json.dump(CASES_DATA, f)
    logf = open(LOG, "w", encoding="utf-8", errors="replace")
    cmd = [JAVA, "-Dfile.encoding=UTF-8", "-Dmpmc.req.control=" + CONTROL,
           "-jar", "ViaProxy-3.4.14.jar", "cli",
           "--bind-address", "127.0.0.1:%d" % PORT,
           "--target-address", "127.0.0.1:19132",
           "--target-version", "Bedrock 1.26.51",
           "--auth-method", "NONE",
           "--ignore-protocol-translation-errors", "true"]
    p = subprocess.Popen(cmd, cwd=DIR, stdout=logf, stderr=subprocess.STDOUT)
    print("gateway pid=%d port=%d" % (p.pid, PORT), flush=True)
    results = []
    try:
        if not wait_port(PORT):
            print("[FATAL] port did not open")
            return 2
        for i, (tag, control) in enumerate(PASSES):
            with open(CONTROL, "w") as f:
                f.write(control)
            marker = os.path.getsize(LOG)
            env = dict(os.environ)
            env.update({"MC_PORT": str(PORT), "MC_USER": "PM%d" % i,
                        "MC_VERSION": "1.21.1", "MC_CASES": CASES,
                        "MC_GAP_MS": "1500", "MC_TAIL_MS": "3500"})
            subprocess.run([NODE, "gui-repro.js"], cwd=CLIENT, env=env,
                           stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, timeout=90)
            time.sleep(1.2)
            with open(LOG, "r", encoding="utf-8", errors="replace") as f:
                f.seek(marker)
                chunk = clean(f.read())
            accepted = len(re.findall(r"accepted item stack request", chunk))
            rejected = re.findall(r"rejected item stack request (-\d+) \(status (\d+)", chunk)
            info = re.findall(r"Creative slot (\d+).*?oldNetId=(\S+)", chunk)
            print("%-20s accepted=%d rejected=%s oldNetIds=%s" % (tag, accepted, rejected, info), flush=True)
            results.append({"tag": tag, "control": control, "accepted": accepted,
                            "rejected": rejected, "oldNetIds": info})
        out = os.path.join(GW, "test-puremove-result.json")
        with open(out, "w") as f:
            json.dump(results, f, ensure_ascii=False, indent=2)
        print("written:", out)
        return 0
    finally:
        p.terminate()
        try:
            p.wait(timeout=15)
        except subprocess.TimeoutExpired:
            p.kill()
        logf.close()


if __name__ == "__main__":
    raise SystemExit(main())
