#!/usr/bin/env python3
"""Dynamic -Dmpmc.audit=true run for the .14 release jar on live BDS."""
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
DIR = os.path.join(GW, "gateway-audit14")
PORT = 25601
LOG = os.path.join(DIR, "vp-audit14.log")

PROBES = [
    ("gui-repro.js", "1.21.1", "AUD14-A"),
    ("status6-burst.js", "1.21.1", "AUD14-B"),
    ("verify-actions.js", "1.21.1", "AUD14-C"),
    ("packet-audit-probe.js", "1.21.4", "AUD14-D"),
    ("gui-repro.js", "1.21.6", "AUD14-E"),
]


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
    cmd = [JAVA, "-Dfile.encoding=UTF-8", "-Dmpmc.audit=true",
           "-jar", "ViaProxy-3.4.14.jar", "cli",
           "--bind-address", "127.0.0.1:%d" % PORT,
           "--target-address", "127.0.0.1:19132",
           "--target-version", "Bedrock 1.26.51",
           "--auth-method", "NONE", "--ignore-protocol-translation-errors", "true"]
    p = subprocess.Popen(cmd, cwd=DIR, stdout=logf, stderr=subprocess.STDOUT)
    print("audit gateway pid=%d port=%d" % (p.pid, PORT), flush=True)
    try:
        if not wait_port(PORT):
            print("[FATAL] port did not open")
            return 2
        for i, (script, version, user) in enumerate(PROBES):
            env = dict(os.environ)
            env.update({"MC_PORT": str(PORT), "MC_USER": user, "MC_VERSION": version,
                        "MC_GAP_MS": "600", "MC_TAIL_MS": "3000"})
            env.pop("MC_CASES", None)
            out = os.path.join(DIR, "probe-%d-%s.txt" % (i, script))
            try:
                with open(out, "w", encoding="utf-8", errors="replace") as f:
                    r = subprocess.run([NODE, script], cwd=CLIENT, env=env,
                                       stdout=f, stderr=subprocess.STDOUT, timeout=120)
                print("%-26s %-8s exit=%d" % (script, version, r.returncode), flush=True)
            except subprocess.TimeoutExpired:
                print("%-26s %-8s TIMEOUT" % (script, version), flush=True)
            time.sleep(2.0)  # gap >= 2 s keeps BDS/RakNet session cleanup happy
        time.sleep(2.0)
    finally:
        p.terminate()
        try:
            p.wait(timeout=15)
        except subprocess.TimeoutExpired:
            p.kill()
        logf.close()
    report = os.path.join(DIR, "audit-report14.txt")
    with open(report, "w", encoding="utf-8", errors="replace") as f:
        r = subprocess.run(["python", os.path.join(ROOT, "audit-report.py"), LOG],
                           stdout=f, stderr=subprocess.STDOUT)
    print("audit-report rc=%d file=%s" % (r.returncode, report), flush=True)
    for line in open(report, encoding="utf-8", errors="replace"):
        if line.startswith(("log:", "PHASES", "PRE vs terminal", "POST toHost",
                            "POST unmapped", "ERROR phase", "non-audit error",
                            "VERDICT", "CLIENTBOUND distinct", "SERVERBOUND distinct")):
            print(line.rstrip(), flush=True)
    return r.returncode


if __name__ == "__main__":
    raise SystemExit(main())
