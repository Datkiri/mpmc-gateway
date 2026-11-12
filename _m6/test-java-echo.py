#!/usr/bin/env python3
"""Prove the Java 1.21.1 client sees the creative slot echo on .14-exp6."""
import os
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
DIR = os.path.join(GW, "gateway-14echo")
PORT = 25596
LOG = os.path.join(DIR, "vp.log")
OUT = os.path.join(DIR, "probe.txt")


def wait_port(port, timeout=90):
    end = time.time() + timeout
    while time.time() < end:
        try:
            with socket.create_connection(("127.0.0.1", port), 0.5):
                return True
        except OSError:
            time.sleep(0.4)
    return False


def main():
    os.makedirs(DIR, exist_ok=True)
    shutil.copy2(NEW_JAR, os.path.join(DIR, "ViaProxy-3.4.14.jar"))
    logf = open(LOG, "w", encoding="utf-8", errors="replace")
    cmd = [JAVA, "-Dfile.encoding=UTF-8", "-jar", "ViaProxy-3.4.14.jar", "cli",
           "--bind-address", "127.0.0.1:%d" % PORT,
           "--target-address", "127.0.0.1:19132",
           "--target-version", "Bedrock 1.26.51",
           "--auth-method", "NONE", "--ignore-protocol-translation-errors", "true"]
    p = subprocess.Popen(cmd, cwd=DIR, stdout=logf, stderr=subprocess.STDOUT)
    print("gateway pid=%d port=%d" % (p.pid, PORT), flush=True)
    try:
        if not wait_port(PORT):
            print("[FATAL] port did not open")
            return 2
        env = dict(os.environ)
        env.update({"MC_PORT": str(PORT), "MC_USER": "ECHO14"})
        with open(OUT, "w", encoding="utf-8", errors="replace") as f:
            r = subprocess.run([NODE, "echo-probe.js"], cwd=CLIENT, env=env,
                               stdout=f, stderr=subprocess.STDOUT, timeout=60)
        print("node_exit=%d" % r.returncode, flush=True)
        for line in open(OUT, encoding="utf-8", errors="replace"):
            if '"ev":"pk"' in line or '"ev":"seen"' in line or '"ev":"done"' in line or '"ev":"sent"' in line or '"ev":"error"' in line:
                print(line.rstrip()[:1000], flush=True)
        return r.returncode
    finally:
        p.terminate()
        try:
            p.wait(timeout=15)
        except subprocess.TimeoutExpired:
            p.kill()
        logf.close()


if __name__ == "__main__":
    raise SystemExit(main())
