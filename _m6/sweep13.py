"""Drive the .13-exp gateway through a list of ITEM_STACK_REQUEST variants.

For each config we start a fresh Java CLI client, so BDS inventory state is
empty and cannot leak between cases. Results are the gateway responses parsed
from the gateway log afterwards; this script only drives and records.
"""
import json
import os
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(ROOT)
CLIENT = os.path.join(PROJECT, "_m5", "client", "creative-slot-probe.js")
NODE = "node"
CONTROL = os.path.join(ROOT, "gw-data", "gateway-exp", "req-control.txt")
LOGDIR = os.path.join(ROOT, "gw-data", "gateway-exp", "sweep-logs")
PORT = os.environ.get("MC_PORT", "25571")
VERSION = os.environ.get("MC_VERSION", "1.21.1")
HOLD = os.environ.get("MC_HOLD_MS", "4200")


def main():
    cfg_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "_m6", "sweep13-baseline.json")
    with open(cfg_path, "r", encoding="utf-8") as f:
        configs = json.load(f)
    os.makedirs(LOGDIR, exist_ok=True)
    tag = os.path.basename(cfg_path).replace(".json", "")
    # Java handshake limits the username to 16 characters; keep it short.
    short = "".join(c for c in tag if c.isalnum())[:6].upper() or "SW"
    print(f"[{time.strftime('%H:%M:%S')}] sweep {tag}: {len(configs)} configs, port {PORT}")
    for i, cfg in enumerate(configs):
        with open(CONTROL, "w", encoding="utf-8") as f:
            f.write(cfg + "\n")
        env = dict(os.environ)
        env.update({
            "MC_PORT": PORT,
            "MC_VERSION": VERSION,
            "MC_USER": f"{short}{i:02d}",
            "HOLD_MS": HOLD,
            "MC_ITEM_IDS": "1",
            "MC_ITEM_COUNT": "16",
        })
        out = os.path.join(LOGDIR, f"{tag}-{i:02d}.log")
        with open(out, "w", encoding="utf-8") as fo:
            p = subprocess.run([NODE, CLIENT], env=env, stdout=fo, stderr=subprocess.STDOUT, timeout=60)
        print(f"[{time.strftime('%H:%M:%S')}] {i:02d} rc={p.returncode} {cfg}")
    print(f"[{time.strftime('%H:%M:%S')}] sweep {tag} done")


if __name__ == "__main__":
    main()
