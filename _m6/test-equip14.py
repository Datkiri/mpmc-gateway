#!/usr/bin/env python3
"""A/B test the .14-exp creative equipment-slot translation on a live BDS.

Gateway 127.0.0.1:25581 -> BDS 127.0.0.1:19132.
Pass A: equip=mob  (new MOB_EQUIPMENT path).
Pass B: equip=request (old ITEM_STACK_REQUEST path, kept for measurement).
Pure CLI: gui-repro.js drives real Java 1.21.1 SET_CREATIVE_MODE_SLOT packets.
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
DIR = os.path.join(GW, "gateway-14eq")
PORT = 25581
CONTROL = os.path.join(DIR, "req-control.txt")
LOG = os.path.join(DIR, "vp.log")
CASES = os.path.join(DIR, "cases.json")

# Java 1.21.1 player inventory slots: 5..8 armor, 45 offhand, 36 normal hotbar.
CASES_DATA = [
    {"tag": "offhand", "slot": 45, "id": 588, "c": 64, "pair": False, "comp": False},
    {"tag": "offhand-pair", "slot": 45, "id": 588, "c": 1, "pair": True, "comp": True},
    {"tag": "armor-head", "slot": 5, "id": 396, "c": 1, "pair": False, "comp": False},
    {"tag": "armor-chest", "slot": 6, "id": 396, "c": 1, "pair": True, "comp": False},
    {"tag": "armor-legs", "slot": 7, "id": 396, "c": 1, "pair": False, "comp": False},
    {"tag": "armor-boots", "slot": 8, "id": 396, "c": 1, "pair": True, "comp": False},
    {"tag": "normal-control", "slot": 36, "id": 396, "c": 64, "pair": True, "comp": True},
    {"tag": "normal-control2", "slot": 37, "id": 588, "c": 64, "pair": False, "comp": False},
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
    cmd = [JAVA, "-Dfile.encoding=UTF-8", "-Dmpmc.audit=true",
           "-Dmpmc.req.control=" + CONTROL,
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
        for i, equip in enumerate(["mob", "request"]):
            with open(CONTROL, "w") as f:
                f.write("equip=" + equip + ",reset=1")
            marker = os.path.getsize(LOG)
            env = dict(os.environ)
            env.update({"MC_PORT": str(PORT), "MC_USER": "EQ14%d" % i,
                        "MC_VERSION": "1.21.1", "MC_CASES": CASES,
                        "MC_GAP_MS": "350", "MC_TAIL_MS": "4000"})
            client_out = os.path.join(DIR, "client-%s.txt" % equip)
            with open(client_out, "w", encoding="utf-8", errors="replace") as co:
                r = subprocess.run([NODE, "gui-repro.js"], cwd=CLIENT, env=env,
                                   stdout=co, stderr=subprocess.STDOUT, timeout=90)
            time.sleep(1.5)
            with open(LOG, "r", encoding="utf-8", errors="replace") as f:
                f.seek(marker)
                chunk = clean(f.read())
            with open(client_out, "r", encoding="utf-8", errors="replace") as f:
                cchunk = f.read()
            rejected = re.findall(r"rejected item stack request (-\d+) \(status (\d+)", chunk)
            accepted = len(re.findall(r"accepted item stack request", chunk))
            equip_lines = len(re.findall(r"via MOB_EQUIPMENT", chunk))
            creative = len(re.findall(r"Creative slot|Creative equipment slot", chunk))
            errors = len(re.findall(r"SEVERE|/ERROR|Exception|Packet violation|PACKET_VIOLATION", chunk))
            client_kicked = len(re.findall(r'"ev":"kicked"|"ev":"error"|"ev":"write_error"', cchunk))
            client_slots = re.findall(r'"clientboundSlots":(\d+)', cchunk)
            # clientbound Bedrock equipment-related packets seen by the proxy
            cb = re.findall(r"\[AUDIT\] PRE (\S+)", chunk)
            equip_cb = [n for n in cb if re.search(r"EQUIP|INVENTORY_CONTENT|INVENTORY_SLOT|STACK_RESPONSE", n, re.I)]
            results.append({"equip": equip, "node_exit": r.returncode,
                            "creative": creative, "accepted": accepted,
                            "rejected": rejected, "mob_equipment_lines": equip_lines,
                            "errors": errors, "client_kicked_or_error": client_kicked,
                            "clientboundSlots": client_slots,
                            "equip_related_clientbound": equip_cb[:40]})
            print("equip=%-8s node_exit=%d creative=%d accepted=%d rejected=%s mob_lines=%d errors=%d client_err=%d cb=%s" %
                  (equip, r.returncode, creative, accepted, rejected, equip_lines, errors,
                   client_kicked, client_slots), flush=True)
        out = os.path.join(GW, "test-equip14-result.json")
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
