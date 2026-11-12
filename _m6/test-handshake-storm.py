#!/usr/bin/env python3
"""Controlled A/B: is the 3/78 CONFIGURATION stall a gateway packet bug or
BDS/RakNet connect-storm behaviour?

FAST = 16 short Java 1.21.1 connections back-to-back (0.2 s gap).
SLOW = 16 short connections with a 2.0 s gap between them.
Same jar, same BDS, same client code. Count how many sessions reach
'Server feature version' (= BDS START_GAME received and decoded).
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
N = 16


def wait_port(port, timeout=90):
    end = time.time() + timeout
    while time.time() < end:
        try:
            with socket.create_connection(("127.0.0.1", port), 0.5):
                return True
        except OSError:
            time.sleep(0.4)
    return False


def run_mode(tag, port, gap):
    d = os.path.join(GW, "gateway-14storm-" + tag)
    os.makedirs(d, exist_ok=True)
    shutil.copy2(NEW_JAR, os.path.join(d, "ViaProxy-3.4.14.jar"))
    log = os.path.join(d, "vp.log")
    logf = open(log, "w", encoding="utf-8", errors="replace")
    cmd = [JAVA, "-Dfile.encoding=UTF-8", "-jar", "ViaProxy-3.4.14.jar", "cli",
           "--bind-address", "127.0.0.1:%d" % port,
           "--target-address", "127.0.0.1:19132",
           "--target-version", "Bedrock 1.26.51",
           "--auth-method", "NONE", "--ignore-protocol-translation-errors", "true"]
    p = subprocess.Popen(cmd, cwd=d, stdout=logf, stderr=subprocess.STDOUT)
    print("[%s] gateway pid=%d port=%d gap=%.1fs" % (tag, p.pid, port, gap), flush=True)
    exits = []
    try:
        if not wait_port(port):
            print("[%s] FATAL port did not open" % tag)
            return None
        for i in range(N):
            env = dict(os.environ)
            env.update({"MC_PORT": str(port), "MC_USER": "ST%s%d" % (tag[0], i),
                        "MC_HOLD_MS": "1200"})
            try:
                r = subprocess.run([NODE, "quick-connect.js"], cwd=CLIENT, env=env,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT,
                                   timeout=20)
                exits.append(r.returncode)
            except subprocess.TimeoutExpired:
                exits.append(-9)
            time.sleep(gap)
        time.sleep(1.5)
        txt = open(log, encoding="utf-8", errors="replace").read()
        txt = re.sub(r"\x1b\[[0-9;]*m", "", txt)
        res = {
            "tag": tag, "gap_s": gap, "n": N,
            "config": len(re.findall(r"Switching to CONFIGURATION state", txt)),
            "finished": len(re.findall(r"Configuration finished", txt)),
            "feature": len(re.findall(r"Server feature version", txt)),
            "unhandled": len(re.findall(r"Caught unhandled netty exception", txt)),
            "proxy_kick": len(re.findall(r"PROXY KICK", txt)),
            "node_timeouts": exits.count(-9),
            "nonzero_node": sum(1 for e in exits if e not in (0, -9)),
        }
        print("[%s] %s" % (tag, json.dumps(res)), flush=True)
        return res
    finally:
        p.terminate()
        try:
            p.wait(timeout=15)
        except subprocess.TimeoutExpired:
            p.kill()
        logf.close()


def run_creative_mode(tag, port, gap, n=20):
    """Same shape as the sweep that produced 3/78 stalls: one creative item
    request per short connection. The only variable changed is the gap between
    connections, so BDS has time to finish the previous ITEM_STACK_REQUEST."""
    d = os.path.join(GW, "gateway-14storm-" + tag)
    os.makedirs(d, exist_ok=True)
    shutil.copy2(NEW_JAR, os.path.join(d, "ViaProxy-3.4.14.jar"))
    cases = os.path.join(d, "cases.json")
    with open(cases, "w") as f:
        json.dump([{"tag": "stone", "slot": 36, "id": 1, "c": 64}], f)
    log = os.path.join(d, "vp.log")
    logf = open(log, "w", encoding="utf-8", errors="replace")
    cmd = [JAVA, "-Dfile.encoding=UTF-8", "-jar", "ViaProxy-3.4.14.jar", "cli",
           "--bind-address", "127.0.0.1:%d" % port,
           "--target-address", "127.0.0.1:19132",
           "--target-version", "Bedrock 1.26.51",
           "--auth-method", "NONE", "--ignore-protocol-translation-errors", "true"]
    p = subprocess.Popen(cmd, cwd=d, stdout=logf, stderr=subprocess.STDOUT)
    print("[%s] gateway pid=%d port=%d gap=%.1fs creative=1" % (tag, p.pid, port, gap), flush=True)
    exits = []
    try:
        if not wait_port(port):
            print("[%s] FATAL port did not open" % tag)
            return None
        for i in range(n):
            env = dict(os.environ)
            env.update({"MC_PORT": str(port), "MC_USER": "CR%s%d" % (tag[0], i),
                        "MC_VERSION": "1.21.1", "MC_CASES": cases,
                        "MC_GAP_MS": "100", "MC_TAIL_MS": "1500"})
            try:
                r = subprocess.run([NODE, "gui-repro.js"], cwd=CLIENT, env=env,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT,
                                   timeout=45)
                exits.append(r.returncode)
            except subprocess.TimeoutExpired:
                exits.append(-9)
            time.sleep(gap)
        time.sleep(1.5)
        txt = open(log, encoding="utf-8", errors="replace").read()
        txt = re.sub(r"\x1b\[[0-9;]*m", "", txt)
        res = {
            "tag": tag, "gap_s": gap, "n": n, "creative_per_conn": 1,
            "config": len(re.findall(r"Switching to CONFIGURATION state", txt)),
            "finished": len(re.findall(r"Configuration finished", txt)),
            "feature": len(re.findall(r"Server feature version", txt)),
            "unhandled": len(re.findall(r"Caught unhandled netty exception", txt)),
            "proxy_kick": len(re.findall(r"PROXY KICK", txt)),
            "node_timeouts": exits.count(-9),
            "nonzero_node": sum(1 for e in exits if e not in (0, -9)),
        }
        print("[%s] %s" % (tag, json.dumps(res)), flush=True)
        return res
    finally:
        p.terminate()
        try:
            p.wait(timeout=15)
        except subprocess.TimeoutExpired:
            p.kill()
        logf.close()


def main():
    fast = run_mode("fast", 25597, 0.2)
    slow = run_mode("slow", 25598, 2.0)
    creative_slow = run_creative_mode("creative-slow", 25599, 2.0)
    out = {"fast": fast, "slow": slow, "creative_slow": creative_slow}
    p = os.path.join(GW, "test-handshake-storm-result.json")
    with open(p, "w") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print("written:", p, flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
