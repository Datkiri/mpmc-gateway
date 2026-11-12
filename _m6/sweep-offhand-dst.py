#!/usr/bin/env python3
"""Sweep destination container/slot/netId for a Java creative offhand click
(slot 45, shield) with NO reset, using .14-exp4 diagnostic knobs on live BDS.

Goal: find any destination BDS accepts, which would reveal the correct
FullContainerName/slot for the Bedrock 1.26.51 offhand slot.
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
DIR = os.path.join(GW, "gateway-14dst")
PORT = 25587
CONTROL = os.path.join(DIR, "req-control.txt")
LOG = os.path.join(DIR, "vp.log")
CASES = os.path.join(DIR, "cases.json")

CASES_DATA = [{"tag": "offhand-shield", "slot": 45, "id": 1162, "c": 1}]

CONFIGS = [
    "dst=OffhandContainer",
    "dst=OffhandContainer,dstSlot=1",
    "dst=OffhandContainer,dstSlot=4",
    "dst=OffhandContainer,dstNetId=1",
    "dst=OffhandContainer,dstNetId=-1",
    "dst=OffhandContainer,dstNetId=-3",
    "dst=CombinedHotbarAndInventoryContainer,dstSlot=45",
    "dst=CombinedHotbarAndInventoryContainer,dstSlot=36",
    "dst=CombinedHotbarAndInventoryContainer,dstSlot=0",
    "dst=HotbarContainer,dstSlot=45",
    "dst=InventoryContainer,dstSlot=45",
    "dst=ArmorContainer,dstSlot=4",
    "dst=CursorContainer",
    "dst=CursorContainer,dstSlot=0",
    "dst=CreatedOutputContainer,dstSlot=50",
    "mode=place,dst=OffhandContainer",
    "mid=CursorContainer,dst=OffhandContainer",
    "swap=true,dst=OffhandContainer",
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
        for i, control in enumerate(CONFIGS):
            with open(CONTROL, "w") as f:
                f.write("equip=request," + control)
            marker = os.path.getsize(LOG)
            env = dict(os.environ)
            env.update({"MC_PORT": str(PORT), "MC_USER": "DS%d" % i,
                        "MC_VERSION": "1.21.1", "MC_CASES": CASES,
                        "MC_GAP_MS": "100", "MC_TAIL_MS": "3000"})
            subprocess.run([NODE, "gui-repro.js"], cwd=CLIENT, env=env,
                           stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, timeout=60)
            time.sleep(0.9)
            with open(LOG, "r", encoding="utf-8", errors="replace") as f:
                f.seek(marker)
                chunk = clean(f.read())
            accepted = len(re.findall(r"accepted item stack request", chunk))
            rejected = re.findall(r"rejected item stack request (-\d+) \(status (\d+)", chunk)
            results.append({"control": control, "accepted": accepted, "rejected": rejected})
            print("%-62s accepted=%d rejected=%s" % (control, accepted, rejected), flush=True)
        out = os.path.join(GW, "sweep-offhand-dst-result.json")
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
