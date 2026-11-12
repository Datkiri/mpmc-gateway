#!/usr/bin/env python3
"""Last sweep: can any ITEM_STACK_REQUEST chain make BDS accept a non-equipment
item (beacon / sniffer_egg) into ArmorContainer or OffhandContainer on 1.26.51?
Uses .14-exp5 diagnostic control-file knobs on live BDS.
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
DIR = os.path.join(GW, "gateway-14inval")
PORT = 25592
CONTROL = os.path.join(DIR, "req-control.txt")
LOG = os.path.join(DIR, "vp.log")
CASES = os.path.join(DIR, "cases.json")

CASES_DATA = [
    {"tag": "off-egg", "slot": 45, "id": 588, "c": 1},
    {"tag": "head-beacon", "slot": 5, "id": 396, "c": 1},
    {"tag": "normal-beacon", "slot": 36, "id": 396, "c": 1},
]

CONFIGS = [
    "mode=full",
    "mode=place",
    "mode=cc-results-take",
    "mode=cc-destroy-take",
    "mode=cc-only",
    "mode=no-cc",
    "mode=results-only",
    "mode=create",
    "mode=amount1",
    "mode=empty-descriptor",
    "mode=descriptor-match",
    "resultsFromCreative=true",
    "resultsFromCreative=true,amount=1",
    "crafts=64",
    "dst=CursorContainer",
    "dst=CombinedHotbarAndInventoryContainer,dstSlot=0",
    "mid=CursorContainer",
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
           "--auth-method", "NONE", "--ignore-protocol-translation-errors", "true"]
    p = subprocess.Popen(cmd, cwd=DIR, stdout=logf, stderr=subprocess.STDOUT)
    print("gateway pid=%d port=%d" % (p.pid, PORT), flush=True)
    results = []
    try:
        if not wait_port(PORT):
            print("[FATAL] port did not open")
            return 2
        for i, control in enumerate(CONFIGS):
            with open(CONTROL, "w") as f:
                f.write(control)
            marker = os.path.getsize(LOG)
            env = dict(os.environ)
            env.update({"MC_PORT": str(PORT), "MC_USER": "IV%d" % i,
                        "MC_VERSION": "1.21.1", "MC_CASES": CASES,
                        "MC_GAP_MS": "250", "MC_TAIL_MS": "3000"})
            subprocess.run([NODE, "gui-repro.js"], cwd=CLIENT, env=env,
                           stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, timeout=90)
            time.sleep(0.9)
            with open(LOG, "r", encoding="utf-8", errors="replace") as f:
                f.seek(marker)
                chunk = clean(f.read())
            accepted = len(re.findall(r"accepted item stack request", chunk))
            rejected = re.findall(r"rejected item stack request (-\d+) \(status (\d+)", chunk)
            results.append({"control": control, "accepted": accepted, "rejected": rejected})
            print("%-58s accepted=%d rejected=%s" % (control, accepted, rejected), flush=True)
        out = os.path.join(GW, "sweep-equip-invalid-result.json")
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
