#!/usr/bin/env python3
"""Is status 55 on equipment slots caused by the ITEM itself or by the COUNT?

Java creative allows arbitrary items with count up to 64 in armor/offhand slots.
Bedrock equipment slots only hold one item. Compare count=1 vs count=64 for
the exact items that were rejected in test-offhand-fix.py.
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
DIR = os.path.join(GW, "gateway-14count")
PORT = 25591
LOG = os.path.join(DIR, "vp.log")
CASES = os.path.join(DIR, "cases.json")

CASES_DATA = [
    {"tag": "off-egg-1", "slot": 45, "id": 588, "c": 1},
    {"tag": "off-egg-64", "slot": 45, "id": 588, "c": 64},
    {"tag": "off-beacon-1", "slot": 45, "id": 396, "c": 1},
    {"tag": "off-beacon-64", "slot": 45, "id": 396, "c": 64},
    {"tag": "head-beacon-1", "slot": 5, "id": 396, "c": 1},
    {"tag": "head-beacon-64", "slot": 5, "id": 396, "c": 64},
    {"tag": "chest-egg-1", "slot": 6, "id": 588, "c": 1},
    {"tag": "chest-egg-64", "slot": 6, "id": 588, "c": 64},
    {"tag": "normal-egg-64", "slot": 36, "id": 588, "c": 64},
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
    cmd = [JAVA, "-Dfile.encoding=UTF-8", "-jar", "ViaProxy-3.4.14.jar", "cli",
           "--bind-address", "127.0.0.1:%d" % PORT,
           "--target-address", "127.0.0.1:19132",
           "--target-version", "Bedrock 1.26.51",
           "--auth-method", "NONE", "--ignore-protocol-translation-errors", "true"]
    p = subprocess.Popen(cmd, cwd=DIR, stdout=logf, stderr=subprocess.STDOUT)
    print("gateway pid=%d port=%d" % (p.pid, PORT), flush=True)
    try:
        if not wait_port(PORT):
            print("[FATAL] port did not open")
            return 2
        env = dict(os.environ)
        env.update({"MC_PORT": str(PORT), "MC_USER": "CNT14", "MC_VERSION": "1.21.1",
                    "MC_CASES": CASES, "MC_GAP_MS": "700", "MC_TAIL_MS": "4000"})
        subprocess.run([NODE, "gui-repro.js"], cwd=CLIENT, env=env,
                       stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, timeout=120)
        time.sleep(1.2)
        with open(LOG, "r", encoding="utf-8", errors="replace") as f:
            chunk = clean(f.read())
        lines = re.findall(r"Creative slot (\d+) -> (\S+) request=(-\d+).*?new=(-?\d+):0x(\d+)", chunk)
        rejected = dict(re.findall(r"rejected item stack request (-\d+) \(status (\d+)", chunk))
        accepted = re.findall(r"accepted item stack request (-\d+)", chunk)
        print("accepted:", accepted)
        print("rejected:", rejected)
        for slot, container, rid, item, cnt in lines:
            print("slot=%-3s %-42s req=%-4s item=%-6s count=%-3s -> %s" %
                  (slot, container, rid, item, cnt,
                   "REJECT " + rejected.get(rid, "") if rid in rejected else
                   ("ACCEPT" if rid in accepted else "?")))
        out = os.path.join(GW, "test-equip-count-result.json")
        with open(out, "w") as f:
            json.dump({"accepted": accepted, "rejected": rejected, "lines": lines}, f, indent=2)
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
