#!/usr/bin/env python3
"""A/B reproduction of Bedrock ITEM_STACK_REQUEST status 6.

Runs the SAME cli driver (gui-repro.js, Java 1.21.1 creative double-click
pattern) against two freshly started ViaProxy gateways that differ only in
the patched ViaBedrock jar: .12 vs .13. Both point at the same live BDS
(127.0.0.1:19132). Prints a per-jar status histogram and the raw evidence
lines so the causal difference can be read from logs, not inferred.

No files are deleted. All artifacts stay under _m6/gw-data/ab*/.
"""
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.abspath(__file__))
GW = os.path.join(ROOT, "gw-data")
CLIENT = os.path.join(os.path.dirname(ROOT), "_m5", "client")
JAVA = r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe"
NODE = r"C:\Users\Acer\.workbuddy-ai\binaries\node\versions\22.22.2-6\node.exe"
BDS = "127.0.0.1:19132"
PROBE = os.environ.get("AB_PROBE", "gui-repro.js")
RESULT = os.environ.get("AB_RESULT", "ab-status6-result.json")
GAP = os.environ.get("AB_GAP_MS", "700")

V12 = os.path.join(GW, "gateway-12", "ViaProxy-3.4.14.jar")
V13 = os.path.join(GW, "gateway-13", "ViaProxy-3.4.14.jar")

JARS = {
    "12": {"jar": V12, "port": 25577, "dir": os.path.join(GW, "ab12"), "user": "AB12A"},
    "13": {"jar": V13, "port": 25578, "dir": os.path.join(GW, "ab13"), "user": "AB13A"},
}


def wait_port(port, timeout=90):
    end = time.time() + timeout
    while time.time() < end:
        try:
            with socket.create_connection(("127.0.0.1", port), 0.5):
                return True
        except OSError:
            time.sleep(0.5)
    return False


def start(tag, cfg):
    os.makedirs(cfg["dir"], exist_ok=True)
    dst = os.path.join(cfg["dir"], "ViaProxy-3.4.14.jar")
    if not os.path.exists(dst) or os.path.getsize(dst) != os.path.getsize(cfg["jar"]):
        shutil.copy2(cfg["jar"], dst)
    log = open(os.path.join(cfg["dir"], "vp.log"), "w", encoding="utf-8", errors="replace")
    cmd = [
        JAVA, "-Dfile.encoding=UTF-8", "-jar", "ViaProxy-3.4.14.jar", "cli",
        "--bind-address", "127.0.0.1:%d" % cfg["port"],
        "--target-address", BDS,
        "--target-version", "Bedrock 1.26.51",
        "--auth-method", "NONE",
        "--ignore-protocol-translation-errors", "true",
    ]
    p = subprocess.Popen(cmd, cwd=cfg["dir"], stdout=log, stderr=subprocess.STDOUT)
    print("[%s] started pid=%d port=%d" % (tag, p.pid, cfg["port"]), flush=True)
    return p, log


def run_probe(tag, cfg):
    env = dict(os.environ)
    env.update({
        "MC_PORT": str(cfg["port"]),
        "MC_USER": cfg["user"],
        "MC_VERSION": "1.21.1",
        "MC_GAP_MS": GAP,
        "MC_TAIL_MS": "6000",
    })
    out = os.path.join(cfg["dir"], "probe.log")
    with open(out, "w", encoding="utf-8", errors="replace") as f:
        r = subprocess.run([NODE, PROBE], cwd=CLIENT, env=env,
                           stdout=f, stderr=subprocess.STDOUT, timeout=120)
    return r.returncode


def analyse(tag, cfg):
    path = os.path.join(cfg["dir"], "vp.log")
    text = open(path, encoding="utf-8", errors="replace").read()
    lines = text.splitlines()
    status = {}
    rejected = []
    accepted = 0
    creative = 0
    errors = []
    for ln in lines:
        if "Creative slot" in ln:
            creative += 1
        if "accepted item stack request" in ln:
            accepted += 1
        m = re.search(r"rejected item stack request (-?\d+) \(status (\d+)", ln)
        if m:
            status[m.group(2)] = status.get(m.group(2), 0) + 1
            rejected.append(ln.strip())
        if "/ERROR" in ln or "Exception" in ln or "SEVERE" in ln:
            errors.append(ln.strip())
    return {
        "tag": tag,
        "probe": PROBE,
        "gap_ms": GAP,
        "jar_sha": None,
        "creative_slot_lines": creative,
        "accepted": accepted,
        "total_rejected": sum(status.values()),
        # .12 has no "accepted" INFO line (added in .13-exp), so the fair
        # cross-version metric is simply "requests BDS did not reject".
        "not_rejected": max(0, creative - sum(status.values())),
        "rejected_by_status": status,
        "rejected_lines": rejected[:6],
        "error_lines": errors[:6],
        "log": path,
    }


def main():
    procs = []
    try:
        for tag in ("12", "13"):
            p, log = start(tag, JARS[tag])
            procs.append((p, log))
        for tag, cfg in JARS.items():
            if not wait_port(cfg["port"]):
                print("[FATAL] port %d did not open" % cfg["port"], flush=True)
                return 2
        for tag, cfg in JARS.items():
            rc = run_probe(tag, cfg)
            print("[%s] probe exit=%d" % (tag, rc), flush=True)
        time.sleep(3)
        results = []
        for tag, cfg in JARS.items():
            r = analyse(tag, cfg)
            r["jar_sha"] = __import__("hashlib").sha256(
                open(cfg["jar"], "rb").read()).hexdigest()
            results.append(r)
        print("\n=== AB RESULT ===")
        print(json.dumps(results, ensure_ascii=False, indent=2))
        out = os.path.join(GW, RESULT)
        with open(out, "w", encoding="utf-8") as f:
            json.dump(results, f, ensure_ascii=False, indent=2)
        print("\nwritten:", out)
        return 0
    finally:
        for p, log in procs:
            if p.poll() is None:
                p.terminate()
                try:
                    p.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    p.kill()
            try:
                log.close()
            except Exception:
                pass
        print("[cleanup] A/B gateways stopped", flush=True)


if __name__ == "__main__":
    sys.exit(main())
