#!/usr/bin/env python3
"""Decide the real cause of status 55/50 on Java creative equipment slots.

Hypothesis: BDS is not rejecting the *container* (ArmorContainer /
OffhandContainer) but the *item* - Java creative allows any item in armor /
offhand slots, Bedrock only allows valid equipment there.

Three passes on one live BDS, one gateway 127.0.0.1:25584:
  A equip=request + VALID equipment items (diamond armor / shield / totem)
  B equip=request + INVALID items (beacon / sniffer_egg)  [control]
  C equip=mob     + VALID equipment items
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
DIR = os.path.join(GW, "gateway-14items")
PORT = 25584
CONTROL = os.path.join(DIR, "req-control.txt")
LOG = os.path.join(DIR, "vp.log")

# Java 1.21.1 ids: stone=1, beacon=396, sniffer_egg=588, diamond_helmet=868,
# diamond_chestplate=869, diamond_leggings=870, diamond_boots=871,
# shield=1162, totem_of_undying=1163.
VALID = [
    {"tag": "head-helmet", "slot": 5, "id": 868, "c": 1},
    {"tag": "chest-chestplate", "slot": 6, "id": 869, "c": 1},
    {"tag": "legs-leggings", "slot": 7, "id": 870, "c": 1},
    {"tag": "boots-boots", "slot": 8, "id": 871, "c": 1},
    {"tag": "offhand-shield", "slot": 45, "id": 1162, "c": 1},
    {"tag": "offhand-totem", "slot": 45, "id": 1163, "c": 1},
    {"tag": "normal-stone", "slot": 36, "id": 1, "c": 64, "pair": True},
]
INVALID = [
    {"tag": "head-beacon", "slot": 5, "id": 396, "c": 1},
    {"tag": "offhand-snifferegg", "slot": 45, "id": 588, "c": 64},
    {"tag": "normal-beacon", "slot": 36, "id": 396, "c": 64},
]

PASSES = [
    ("request", VALID, "A-request-valid"),
    ("request", INVALID, "B-request-invalid"),
    ("mob", VALID, "C-mob-valid"),
    ("mob", INVALID, "D-mob-invalid"),
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
        for i, (equip, cases, tag) in enumerate(PASSES):
            case_path = os.path.join(DIR, "cases-%s.json" % tag)
            with open(case_path, "w") as f:
                json.dump(cases, f)
            with open(CONTROL, "w") as f:
                f.write("equip=" + equip + ",reset=1")
            marker = os.path.getsize(LOG)
            env = dict(os.environ)
            env.update({"MC_PORT": str(PORT), "MC_USER": "IT14%d" % i,
                        "MC_VERSION": "1.21.1", "MC_CASES": case_path,
                        "MC_GAP_MS": "400", "MC_TAIL_MS": "3500"})
            client_out = os.path.join(DIR, "client-%s.txt" % tag)
            with open(client_out, "w", encoding="utf-8", errors="replace") as co:
                r = subprocess.run([NODE, "gui-repro.js"], cwd=CLIENT, env=env,
                                   stdout=co, stderr=subprocess.STDOUT, timeout=90)
            time.sleep(1.2)
            with open(LOG, "r", encoding="utf-8", errors="replace") as f:
                f.seek(marker)
                chunk = clean(f.read())
            rejected = re.findall(r"rejected item stack request (-\d+) \(status (\d+)", chunk)
            accepted = len(re.findall(r"accepted item stack request", chunk))
            mob_lines = len(re.findall(r"via MOB_EQUIPMENT", chunk))
            errors = len(re.findall(r"SEVERE|/ERROR|Exception|Packet violation", chunk))
            with open(client_out, "r", encoding="utf-8", errors="replace") as f:
                cchunk = f.read()
            client_err = len(re.findall(r'"ev":"kicked"|"ev":"error"|"ev":"write_error"', cchunk))
            results.append({"tag": tag, "equip": equip, "cases": [c["tag"] for c in cases],
                            "node_exit": r.returncode, "accepted": accepted,
                            "rejected": rejected, "mob_equipment_lines": mob_lines,
                            "errors": errors, "client_err": client_err})
            print("%-20s equip=%-8s accepted=%d rejected=%s mob=%d errors=%d client_err=%d" %
                  (tag, equip, accepted, rejected, mob_lines, errors, client_err), flush=True)
        out = os.path.join(GW, "test-equip-items-result.json")
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
