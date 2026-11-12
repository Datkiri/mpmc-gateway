#!/usr/bin/env python3
"""Prove that OffhandContainer/1 is the REAL Bedrock offhand slot.

GW-A 25588: equipper, control forces dst=OffhandContainer,dstSlot=1.
GW-B 25589: passive observer, audit + Java packet log.

Expected if correct: observer receives entity_equipment slot=1 with a
non-empty item (shield, Java id 1162) from BDS, and GW-B audit shows a
clientbound MOB_EQUIPMENT broadcast. Control pass uses the buggy slot 0
and should show no item.
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

DIR_A = os.path.join(GW, "gateway-14off1A")
DIR_B = os.path.join(GW, "gateway-14off1B")
PORT_A, PORT_B = 25588, 25589
LOG_A = os.path.join(DIR_A, "vp.log")
LOG_B = os.path.join(DIR_B, "vp.log")
CONTROL_A = os.path.join(DIR_A, "req-control.txt")
CONTROL_B = os.path.join(DIR_B, "req-control.txt")
CASES = os.path.join(DIR_A, "cases.json")

CASES_DATA = [
    {"tag": "offhand-shield", "slot": 45, "id": 1162, "c": 1},
    {"tag": "offhand-totem", "slot": 45, "id": 1163, "c": 1},
]

PASSES = [
    ("slot1", "equip=request,dst=OffhandContainer,dstSlot=1"),
    ("slot0-control", "equip=request,dst=OffhandContainer,dstSlot=0"),
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


def start_gateway(directory, port, control, log):
    os.makedirs(directory, exist_ok=True)
    shutil.copy2(NEW_JAR, os.path.join(directory, "ViaProxy-3.4.14.jar"))
    f = open(log, "w", encoding="utf-8", errors="replace")
    cmd = [JAVA, "-Dfile.encoding=UTF-8", "-Dmpmc.audit=true",
           "-Dmpmc.req.control=" + control,
           "-jar", "ViaProxy-3.4.14.jar", "cli",
           "--bind-address", "127.0.0.1:%d" % port,
           "--target-address", "127.0.0.1:19132",
           "--target-version", "Bedrock 1.26.51",
           "--auth-method", "NONE",
           "--ignore-protocol-translation-errors", "true"]
    return subprocess.Popen(cmd, cwd=directory, stdout=f, stderr=subprocess.STDOUT), f


def main():
    os.makedirs(DIR_A, exist_ok=True)
    os.makedirs(DIR_B, exist_ok=True)
    with open(CASES, "w") as f:
        json.dump(CASES_DATA, f)
    results = []
    for i, (tag, control) in enumerate(PASSES):
        with open(CONTROL_A, "w") as f:
            f.write(control)
        pa, fa = start_gateway(DIR_A, PORT_A, CONTROL_A, LOG_A)
        pb, fb = start_gateway(DIR_B, PORT_B, CONTROL_B, LOG_B)
        observer_out = os.path.join(DIR_B, "observer-%s.txt" % tag)
        try:
            if not (wait_port(PORT_A) and wait_port(PORT_B)):
                print("[FATAL] ports did not open")
                return 2
            env_b = dict(os.environ)
            env_b.update({"MC_PORT": str(PORT_B), "MC_USER": "OBS1%d" % i,
                          "MC_VERSION": "1.21.1", "MC_HOLD_MS": "12000"})
            with open(observer_out, "w", encoding="utf-8", errors="replace") as ob:
                po = subprocess.Popen([NODE, "equip-observer.js"], cwd=CLIENT, env=env_b,
                                      stdout=ob, stderr=subprocess.STDOUT)
            time.sleep(3.0)
            marker_a = os.path.getsize(LOG_A)
            marker_b = os.path.getsize(LOG_B)
            env_a = dict(os.environ)
            env_a.update({"MC_PORT": str(PORT_A), "MC_USER": "OFF1%d" % i,
                          "MC_VERSION": "1.21.1", "MC_CASES": CASES,
                          "MC_GAP_MS": "1200", "MC_TAIL_MS": "4000"})
            subprocess.run([NODE, "gui-repro.js"], cwd=CLIENT, env=env_a,
                           stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, timeout=90)
            try:
                po.wait(timeout=18)
            except subprocess.TimeoutExpired:
                po.kill()
            time.sleep(0.4)
            with open(LOG_A, "r", encoding="utf-8", errors="replace") as f:
                f.seek(marker_a); chunk_a = clean(f.read())
            with open(LOG_B, "r", encoding="utf-8", errors="replace") as f:
                f.seek(marker_b); chunk_b = clean(f.read())
            with open(observer_out, "r", encoding="utf-8", errors="replace") as f:
                obs = f.read()
            accepted = len(re.findall(r"accepted item stack request", chunk_a))
            rejected = re.findall(r"rejected item stack request (-\d+) \(status (\d+)", chunk_a)
            pre_names = re.findall(r"\[AUDIT\] PRE dir=CLIENTBOUND[^\n]*?name=([A-Z0-9_]+)", chunk_b)
            mob_broadcasts = pre_names.count("MOB_EQUIPMENT")
            # Java entity_equipment lines carrying a NON-EMPTY offhand item.
            offhand_items = re.findall(r'"slot":1,"item":\{"itemCount":(\d+),"itemId":(\d+)', obs)
            all_equipment = [line for line in obs.splitlines() if '"entity_equipment"' in line]
            results.append({"tag": tag, "control": control, "accepted": accepted,
                            "rejected": rejected, "gwB_MOB_EQUIPMENT": mob_broadcasts,
                            "observer_offhand_items": offhand_items,
                            "observer_equipment_lines": all_equipment[-6:]})
            print("%-14s accepted=%d rejected=%s mobBroadcast=%d offhandItems=%s" %
                  (tag, accepted, rejected, mob_broadcasts, offhand_items), flush=True)
        finally:
            for p in (pa, pb):
                p.terminate()
            for p in (pa, pb):
                try:
                    p.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    p.kill()
            fa.close(); fb.close()
        time.sleep(1.0)
    out = os.path.join(GW, "test-offhand-slot1-result.json")
    with open(out, "w") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print("written:", out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
