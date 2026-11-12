#!/usr/bin/env python3
"""Comprehensive live-BDS verification of the .14 offhand slot-1 fix.

Gateway 127.0.0.1:25590 -> BDS 127.0.0.1:19132, NO control file (production
configuration: all diagnostic knobs at defaults).

Passes:
  A gui-repro equipment cases (valid armor/offhand, invalid items, pairs)
  B status6-burst.js default cases (the original burst that exposed status 50
    on Java slot 45 / minecraft:sniffer_egg)
  C gui-repro.js default cases (regression on normal inventory slots)
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
DIR = os.path.join(GW, "gateway-14fix")
PORT = 25590
LOG = os.path.join(DIR, "vp.log")
EQUIP_CASES = os.path.join(DIR, "equip-cases.json")

EQUIP_CASES_DATA = [
    # Valid Bedrock equipment items.
    {"tag": "offhand-shield", "slot": 45, "id": 1162, "c": 1},
    {"tag": "offhand-totem", "slot": 45, "id": 1163, "c": 1},
    {"tag": "offhand-shield-pair", "slot": 45, "id": 1162, "c": 1, "pair": True},
    {"tag": "head-helmet", "slot": 5, "id": 868, "c": 1},
    {"tag": "chest-chestplate", "slot": 6, "id": 869, "c": 1},
    {"tag": "legs-leggings", "slot": 7, "id": 870, "c": 1},
    {"tag": "boots-boots", "slot": 8, "id": 871, "c": 1},
    {"tag": "armor-pair", "slot": 8, "id": 871, "c": 1, "pair": True},
    # Java creative allows arbitrary items in equipment slots. These are the
    # exact items that used to trigger status 50/55 in the GUI burst test.
    {"tag": "offhand-snifferegg", "slot": 45, "id": 588, "c": 64},
    {"tag": "offhand-beacon", "slot": 45, "id": 396, "c": 64},
    {"tag": "head-beacon", "slot": 5, "id": 396, "c": 64},
    {"tag": "chest-snifferegg", "slot": 6, "id": 588, "c": 64},
    # Normal inventory controls.
    {"tag": "normal-stone", "slot": 36, "id": 1, "c": 64, "pair": True},
    {"tag": "normal-beacon", "slot": 37, "id": 396, "c": 64},
]

PASSES = [
    ("A-equipment", "gui-repro.js", EQUIP_CASES, "400", "4000"),
    ("B-status6-burst", "status6-burst.js", "", "0", "6000"),
    ("C-regression", "gui-repro.js", "", "1200", "3000"),
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
    with open(EQUIP_CASES, "w") as f:
        json.dump(EQUIP_CASES_DATA, f)
    logf = open(LOG, "w", encoding="utf-8", errors="replace")
    cmd = [JAVA, "-Dfile.encoding=UTF-8",
           "-jar", "ViaProxy-3.4.14.jar", "cli",
           "--bind-address", "127.0.0.1:%d" % PORT,
           "--target-address", "127.0.0.1:19132",
           "--target-version", "Bedrock 1.26.51",
           "--auth-method", "NONE",
           "--ignore-protocol-translation-errors", "true"]
    p = subprocess.Popen(cmd, cwd=DIR, stdout=logf, stderr=subprocess.STDOUT)
    print("gateway pid=%d port=%d (no control file)" % (p.pid, PORT), flush=True)
    results = []
    try:
        if not wait_port(PORT):
            print("[FATAL] port did not open")
            return 2
        for i, (tag, script, case_file, gap, tail) in enumerate(PASSES):
            marker = os.path.getsize(LOG)
            env = dict(os.environ)
            env.update({"MC_PORT": str(PORT), "MC_USER": "FX14%d" % i,
                        "MC_VERSION": "1.21.1", "MC_GAP_MS": gap, "MC_TAIL_MS": tail})
            if case_file:
                env["MC_CASES"] = case_file
            else:
                env.pop("MC_CASES", None)
            client_out = os.path.join(DIR, "client-%s.txt" % tag)
            with open(client_out, "w", encoding="utf-8", errors="replace") as co:
                r = subprocess.run([NODE, script], cwd=CLIENT, env=env,
                                   stdout=co, stderr=subprocess.STDOUT, timeout=120)
            time.sleep(1.2)
            with open(LOG, "r", encoding="utf-8", errors="replace") as f:
                f.seek(marker)
                chunk = clean(f.read())
            with open(client_out, "r", encoding="utf-8", errors="replace") as f:
                cchunk = f.read()
            rejected = re.findall(r"rejected item stack request (-\d+) \(status (\d+)", chunk)
            accepted = len(re.findall(r"accepted item stack request", chunk))
            creative = len(re.findall(r"Creative slot ", chunk))
            errors = len(re.findall(r"SEVERE|/ERROR|Exception|Packet violation|PACKET_VIOLATION", chunk))
            client_err = len(re.findall(r'"ev":"kicked"|"ev":"error"|"ev":"write_error"', cchunk))
            timeouts = len(re.findall(r"request timed out", chunk))
            statuses = {}
            for _, st in rejected:
                statuses[st] = statuses.get(st, 0) + 1
            results.append({"tag": tag, "script": script, "node_exit": r.returncode,
                            "creative": creative, "accepted": accepted, "rejected": rejected,
                            "statuses": statuses, "errors": errors, "client_err": client_err,
                            "timeouts": timeouts})
            print("%-18s exit=%d creative=%d accepted=%d rejected=%d statuses=%s errors=%d client_err=%d timeouts=%d" %
                  (tag, r.returncode, creative, accepted, len(rejected), statuses, errors,
                   client_err, timeouts), flush=True)
        out = os.path.join(GW, "test-offhand-fix-result.json")
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
