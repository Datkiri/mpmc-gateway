#!/usr/bin/env python3
"""Live-BDS verification of .14-exp6 (equipment eligibility + item aliases).

Gateway 127.0.0.1:25595 -> BDS 127.0.0.1:19132, NO control file (production path).

Acceptance:
  - valid equipment items are accepted by BDS (no status 55);
  - non-equippable items in equipment slots are [CLASSIFIED] and NOT sent;
  - spectral_arrow / filled_map no longer vanish (no silent drop / no WARN);
  - zero rejections, zero errors, zero silent drops, zero client kicks;
  - gui-repro + status6-burst + default regression all exit 0.
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
DIR = os.path.join(GW, "gateway-14final")
PORT = 25600
LOG = os.path.join(DIR, "vp.log")
EQUIP_CASES = os.path.join(DIR, "equip-cases.json")
EDGE_CASES = os.path.join(DIR, "edge-cases.json")

EQUIP_CASES_DATA = [
    # Valid Bedrock equipment items.
    {"tag": "offhand-shield", "slot": 45, "id": 1162, "c": 1},
    {"tag": "offhand-totem", "slot": 45, "id": 1163, "c": 1},
    {"tag": "offhand-shield-pair", "slot": 45, "id": 1162, "c": 1, "pair": True},
    {"tag": "offhand-nautilus", "slot": 45, "id": 1190, "c": 1},
    {"tag": "head-helmet", "slot": 5, "id": 868, "c": 1},
    {"tag": "head-pumpkin", "slot": 5, "id": 323, "c": 1},
    {"tag": "chest-chestplate", "slot": 6, "id": 869, "c": 1},
    {"tag": "chest-elytra", "slot": 6, "id": 773, "c": 1},
    {"tag": "legs-leggings", "slot": 7, "id": 870, "c": 1},
    {"tag": "boots-boots", "slot": 8, "id": 871, "c": 1},
    {"tag": "armor-pair", "slot": 8, "id": 871, "c": 1, "pair": True},
    # Java creative allows arbitrary items in equipment slots: these used to
    # produce status 50/55. Now they must be [CLASSIFIED], never rejected.
    {"tag": "offhand-snifferegg", "slot": 45, "id": 588, "c": 64},
    {"tag": "offhand-beacon", "slot": 45, "id": 396, "c": 64},
    {"tag": "head-beacon", "slot": 5, "id": 396, "c": 64},
    {"tag": "chest-snifferegg", "slot": 6, "id": 588, "c": 64},
    {"tag": "offhand-stone", "slot": 45, "id": 1, "c": 64},
    # Normal inventory controls.
    {"tag": "normal-stone", "slot": 36, "id": 1, "c": 64, "pair": True},
    {"tag": "normal-beacon", "slot": 37, "id": 396, "c": 64},
]

EDGE_CASES_DATA = [
    # The two items that previously vanished silently.
    {"tag": "spectral-arrow-inventory", "slot": 36, "id": 1159, "c": 1},
    {"tag": "spectral-arrow-offhand", "slot": 45, "id": 1159, "c": 1},
    {"tag": "filled-map-inventory", "slot": 37, "id": 982, "c": 1},
    {"tag": "filled-map-pair", "slot": 38, "id": 982, "c": 1, "pair": True},
    {"tag": "tipped-arrow-offhand", "slot": 45, "id": 1160, "c": 1},
    {"tag": "map-offhand", "slot": 45, "id": 1101, "c": 1},
    {"tag": "firework-offhand", "slot": 45, "id": 1112, "c": 1},
    {"tag": "arrow-offhand", "slot": 45, "id": 802, "c": 1},
]

PASSES = [
    ("A-equipment", "gui-repro.js", EQUIP_CASES, "400", "4000"),
    ("B-alias-edge", "gui-repro.js", EDGE_CASES, "400", "4000"),
    ("C-status6-burst", "status6-burst.js", "", "0", "6000"),
    ("D-regression", "gui-repro.js", "", "1200", "3000"),
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
    with open(EDGE_CASES, "w") as f:
        json.dump(EDGE_CASES_DATA, f)
    control = os.path.join(DIR, "req-control.txt")
    if os.path.exists(control):
        os.remove(control)
    logf = open(LOG, "w", encoding="utf-8", errors="replace")
    cmd = [JAVA, "-Dfile.encoding=UTF-8", "-jar", "ViaProxy-3.4.14.jar", "cli",
           "--bind-address", "127.0.0.1:%d" % PORT,
           "--target-address", "127.0.0.1:19132",
           "--target-version", "Bedrock 1.26.51",
           "--auth-method", "NONE", "--ignore-protocol-translation-errors", "true"]
    p = subprocess.Popen(cmd, cwd=DIR, stdout=logf, stderr=subprocess.STDOUT)
    print("gateway pid=%d port=%d jar=.14 release (no control file)" % (p.pid, PORT), flush=True)
    results = []
    try:
        if not wait_port(PORT):
            print("[FATAL] port did not open")
            return 2
        for i, (tag, script, case_file, gap, tail) in enumerate(PASSES):
            marker = os.path.getsize(LOG)
            env = dict(os.environ)
            env.update({"MC_PORT": str(PORT), "MC_USER": "X14E%d" % i,
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
            classified = len(re.findall(r"\[CLASSIFIED\]", chunk))
            silent_drop = len(re.findall(r"slot update is dropped|Could not find creative item", chunk))
            missing_map = len(re.findall(r"Missing Java -> Bedrock item mapping", chunk))
            errors = len(re.findall(r"SEVERE|/ERROR|Exception|Packet violation|PACKET_VIOLATION", chunk))
            client_err = len(re.findall(r'"ev":"kicked"|"ev":"error"|"ev":"write_error"', cchunk))
            timeouts = len(re.findall(r"request timed out", chunk))
            statuses = {}
            for _, st in rejected:
                statuses[st] = statuses.get(st, 0) + 1
            results.append({"tag": tag, "script": script, "node_exit": r.returncode,
                            "creative": creative, "accepted": accepted, "rejected": rejected,
                            "statuses": statuses, "classified": classified,
                            "silent_drop": silent_drop, "missing_map": missing_map,
                            "errors": errors, "client_err": client_err, "timeouts": timeouts})
            print("%-18s exit=%d creative=%d accepted=%d rejected=%d statuses=%s classified=%d "
                  "silent_drop=%d missing_map=%d errors=%d client_err=%d timeouts=%d" %
                  (tag, r.returncode, creative, accepted, len(rejected), statuses, classified,
                   silent_drop, missing_map, errors, client_err, timeouts), flush=True)
        out = os.path.join(GW, "test-mpmc14-result.json")
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
