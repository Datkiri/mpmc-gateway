#!/usr/bin/env python3
"""Find an ITEM_STACK_REQUEST chain BDS accepts for Java creative slot 45
(offhand) using VALID Bedrock offhand items (shield / totem), plus an armor
control with a valid helmet. Earlier sweeps used beacon/sniffer_egg, which BDS
rejects as CannotPlaceItem regardless of the container - those results were
misleading. Uses the .14-exp2 diagnostic control-file knobs on one live BDS.
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
DIR = os.path.join(GW, "gateway-14offh")
PORT = 25585
CONTROL = os.path.join(DIR, "req-control.txt")
LOG = os.path.join(DIR, "vp.log")
CASE_DIR = os.path.join(CLIENT, "sweep-cases")

CASES = {
    "offhand-shield": [{"tag": "offhand-shield", "slot": 45, "id": 1162, "c": 1}],
    "offhand-totem": [{"tag": "offhand-totem", "slot": 45, "id": 1163, "c": 1}],
    "armor-helmet": [{"tag": "armor-helmet", "slot": 5, "id": 868, "c": 1}],
    "normal-stone": [{"tag": "normal-stone", "slot": 36, "id": 1, "c": 64}],
}

CONFIGS = [
    ("offhand-shield", "mode=full,equip=request,reset=1"),
    ("offhand-shield", "mode=place,equip=request,reset=1"),
    ("offhand-shield", "mode=cc-results-take,equip=request,reset=1"),
    ("offhand-shield", "dst=CursorContainer,equip=request,reset=1"),
    ("offhand-shield", "dst=CombinedHotbarAndInventoryContainer,dstSlot=0,equip=request,reset=1"),
    ("offhand-shield", "dst=HotbarContainer,dstSlot=0,equip=request,reset=1"),
    ("offhand-shield", "mid=CursorContainer,equip=request,reset=1"),
    ("offhand-shield", "swap=true,equip=request,reset=1"),
    ("offhand-shield", "dstNetId=1,equip=request,reset=1"),
    ("offhand-shield", "src=CombinedHotbarAndInventoryContainer,srcSlot=0,srcNetId=0,equip=request,reset=1"),
    ("offhand-totem", "mode=full,equip=request,reset=1"),
    ("offhand-totem", "mode=place,equip=request,reset=1"),
    ("offhand-totem", "dst=CursorContainer,equip=request,reset=1"),
    ("armor-helmet", "mode=full,equip=request,reset=1"),
    ("armor-helmet", "mode=place,equip=request,reset=1"),
    ("normal-stone", "mode=full,equip=request,reset=1"),
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
    os.makedirs(CASE_DIR, exist_ok=True)
    for name, cases in CASES.items():
        with open(os.path.join(CASE_DIR, name + ".json"), "w") as f:
            json.dump(cases, f)
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
        for i, (case, control) in enumerate(CONFIGS):
            with open(CONTROL, "w") as f:
                f.write(control)
            marker = os.path.getsize(LOG)
            env = dict(os.environ)
            env.update({"MC_PORT": str(PORT), "MC_USER": "OH%d" % i,
                        "MC_VERSION": "1.21.1",
                        "MC_CASES": os.path.join(CASE_DIR, case + ".json"),
                        "MC_GAP_MS": "100", "MC_TAIL_MS": "3500"})
            subprocess.run([NODE, "gui-repro.js"], cwd=CLIENT, env=env,
                           stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, timeout=60)
            time.sleep(1.0)
            with open(LOG, "r", encoding="utf-8", errors="replace") as f:
                f.seek(marker)
                chunk = clean(f.read())
            status = re.findall(r"rejected item stack request (-\d+) \(status (\d+)", chunk)
            accepted = len(re.findall(r"accepted item stack request", chunk))
            errors = len(re.findall(r"SEVERE|/ERROR|Exception", chunk))
            results.append({"case": case, "control": control, "accepted": accepted,
                            "rejected": status, "errors": errors})
            print("%-16s %-78s accepted=%d rejected=%s errors=%d" %
                  (case, control, accepted, status, errors), flush=True)
        out = os.path.join(GW, "sweep-offhand-valid-result.json")
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
