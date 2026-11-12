#!/usr/bin/env python3
"""Prove whether BDS actually applies the .14-exp creative equipment translation.

Two gateways share one live BDS:
  GW-A 25582: equipper (Java 1.21.1 CLI driver sends SET_CREATIVE_MODE_SLOT).
  GW-B 25583: passive observer; its audit log records every Bedrock clientbound
              packet BDS sends to a *different* player.

Positive control: a held_item_slot change makes BDS broadcast MOB_EQUIPMENT.
If the observer sees that but NOT the offhand/armor change, BDS silently
ignored our equipment packet and the fix is only cosmetic (must be rejected).
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

DIR_A = os.path.join(GW, "gateway-14eqA")
DIR_B = os.path.join(GW, "gateway-14eqB")
PORT_A, PORT_B = 25582, 25583
CONTROL_A = os.path.join(DIR_A, "req-control.txt")
LOG_A = os.path.join(DIR_A, "vp.log")
LOG_B = os.path.join(DIR_B, "vp.log")
CASES = os.path.join(DIR_A, "cases.json")

CASES_DATA = [
    # Positive control: held_item_slot -> real Bedrock MOB_EQUIPMENT broadcast.
    {"tag": "held-control", "slot": 36, "id": 1, "c": 64, "pair": False, "held": 3},
    # VALID Bedrock offhand items. Earlier observer runs used sniffer_egg,
    # which BDS refuses in the offhand slot, so "empty item" was inconclusive.
    {"tag": "offhand-shield", "slot": 45, "id": 1162, "c": 1, "pair": False},
    {"tag": "offhand-totem", "slot": 45, "id": 1163, "c": 1, "pair": False},
    {"tag": "armor-head-helmet", "slot": 5, "id": 868, "c": 1, "pair": False},
    {"tag": "armor-chest", "slot": 6, "id": 869, "c": 1, "pair": False},
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
    for equip in ["mob", "request"]:
        with open(CONTROL_A, "w") as f:
            f.write("equip=" + equip + ",reset=1")
        pa, fa = start_gateway(DIR_A, PORT_A, CONTROL_A, LOG_A)
        pb, fb = start_gateway(DIR_B, PORT_B, os.path.join(DIR_B, "control.txt"), LOG_B)
        observer_out = os.path.join(DIR_B, "observer-%s.txt" % equip)
        equipper_out = os.path.join(DIR_A, "equipper-%s.txt" % equip)
        try:
            if not (wait_port(PORT_A) and wait_port(PORT_B)):
                print("[FATAL] ports did not open")
                return 2
            env_b = dict(os.environ)
            env_b.update({"MC_PORT": str(PORT_B), "MC_USER": "OBS%s" % equip,
                          "MC_VERSION": "1.21.1", "MC_HOLD_MS": "15000"})
            with open(observer_out, "w", encoding="utf-8", errors="replace") as ob:
                po = subprocess.Popen([NODE, "equip-observer.js"], cwd=CLIENT, env=env_b,
                                      stdout=ob, stderr=subprocess.STDOUT)
            time.sleep(3.0)  # let observer reach PLAY before equipper acts
            marker_b = os.path.getsize(LOG_B)
            env_a = dict(os.environ)
            env_a.update({"MC_PORT": str(PORT_A), "MC_USER": "EQP%s" % equip,
                          "MC_VERSION": "1.21.1", "MC_CASES": CASES,
                          "MC_GAP_MS": "500", "MC_TAIL_MS": "5000"})
            with open(equipper_out, "w", encoding="utf-8", errors="replace") as eo:
                ra = subprocess.run([NODE, "gui-repro.js"], cwd=CLIENT, env=env_a,
                                    stdout=eo, stderr=subprocess.STDOUT, timeout=90)
            try:
                po.wait(timeout=20)
            except subprocess.TimeoutExpired:
                po.kill()
            time.sleep(0.5)
            with open(LOG_B, "r", encoding="utf-8", errors="replace") as f:
                f.seek(marker_b)
                chunk_b = clean(f.read())
            with open(LOG_A, "r", encoding="utf-8", errors="replace") as f:
                chunk_a = clean(f.read())
            pre_names = re.findall(r"\[AUDIT\] PRE dir=CLIENTBOUND[^\n]*?name=([A-Z0-9_]+)", chunk_b)
            equip_hits = [n for n in pre_names if re.search(r"EQUIP|INVENTORY_CONTENT|INVENTORY_SLOT|ITEM_STACK_RESPONSE", n, re.I)]
            with open(observer_out, "r", encoding="utf-8", errors="replace") as f:
                obs = f.read()
            java_equipment = len(re.findall(r'"name":"(?:entity_equipment|set_slot|window_items)"', obs))
            mob_lines = len(re.findall(r"via MOB_EQUIPMENT", chunk_a))
            rejected = len(re.findall(r"rejected item stack request", chunk_a))
            results.append({"equip": equip, "equipper_exit": ra.returncode,
                            "gwA_mob_equipment_lines": mob_lines, "gwA_rejected": rejected,
                            "gwB_clientbound_equip_related": equip_hits,
                            "gwB_pre_total": len(pre_names),
                            "observer_java_equipment_packets": java_equipment,
                            "observer_log_tail": obs.strip().splitlines()[-6:]})
            print("equip=%-8s mob_lines=%d rejected=%d gwB_equip=%s observer_java_equip=%d" %
                  (equip, mob_lines, rejected, equip_hits, java_equipment), flush=True)
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
    out = os.path.join(GW, "test-equip-observe-result.json")
    with open(out, "w") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print("written:", out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
