#!/usr/bin/env python3
"""Measure the exact BDS 1.26.51 boundary for ITEM_STACK_REQUEST into equipment
containers. Runs the *production* path: no control file, .14-exp5 offhand wire
slot 1 fix active. Each item gets a fresh Java 1.21.1 connection so a rejected
request can never poison the next case with an optimistic oldItem.netId().

Output: gw-data/sweep-equip-boundary-result.json
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
SWEEP = os.environ.get("SWEEP", "1")
SUFFIX = "" if SWEEP == "1" else "-wl"
DIR = os.path.join(GW, "gateway-14bound" + SUFFIX)
PORT = 25593 if SWEEP == "1" else 25594
CASES = os.path.join(DIR, "cases.json")
LOG = os.path.join(DIR, "vp.log")
RESULT = os.path.join(GW, "sweep-equip-boundary%s-result.json" % SUFFIX)

ITEMS = {
    "stone": 1, "dirt": 28, "cobblestone": 35, "oak_planks": 36,
    "torch": 291, "carved_pumpkin": 323, "beacon": 396, "prismarine": 503,
    "sniffer_egg": 588, "damaged_anvil": 421, "saddle": 765, "elytra": 773,
    "apple": 800, "arrow": 802, "diamond_sword": 838, "diamond_helmet": 868,
    "diamond_chestplate": 869, "water_bucket": 909, "book": 925, "egg": 927,
    "bundle": 930, "clock": 932, "spyglass": 933, "map": 1101,
    "player_head": 1105, "firework_rocket": 1112, "turtle_helmet": 794,
    "spectral_arrow": 1159, "tipped_arrow": 1160, "shield": 1162,
    "totem_of_undying": 1163, "crossbow": 1192, "composter": 1204,
    # Extra variants for the equippable whitelist (SWEEP=2).
    "netherite_helmet": 876, "leather_helmet": 856, "iron_helmet": 864,
    "golden_helmet": 872, "chainmail_helmet": 860, "netherite_chestplate": 877,
    "netherite_leggings": 878, "netherite_boots": 879, "dragon_head": 1108,
    "creeper_head": 1107, "zombie_head": 1106, "skeleton_skull": 1103,
    "wither_skeleton_skull": 1104, "piglin_head": 1109, "jack_o_lantern": 324,
    "filled_map": 982, "nautilus_shell": 1190, "ender_pearl": 993, "egg": 927,
    "snowball": 912, "water_bucket": 909, "book": 925, "clock": 932,
    "compass": 928, "lead": 1128, "name_tag": 1129, "saddle": 765,
    "oak_boat": 774, "chest": 299, "torch": 291, "white_wool": 202,
    "iron_ingot": 811, "stick": 848, "flint_and_steel": 798,
    "fishing_rod": 931, "bow": 801, "shears": 983, "brush": 1268,
    "goat_horn": 1203, "spyglass": 933,
}

# (tag, javaSlot, itemName) -- offhand + armor head + inventory control.
CASES_DATA = []
if SWEEP == "1":
    for name in ["stone", "cobblestone", "oak_planks", "diamond_sword", "apple",
                 "shield", "totem_of_undying", "arrow", "tipped_arrow", "spectral_arrow",
                 "firework_rocket", "map", "crossbow", "elytra", "carved_pumpkin",
                 "player_head", "diamond_helmet", "beacon", "sniffer_egg", "composter",
                 "damaged_anvil", "prismarine"]:
        CASES_DATA.append(("offhand-" + name, 45, name))
    for name in ["diamond_helmet", "turtle_helmet", "carved_pumpkin", "player_head",
                 "elytra", "stone", "beacon", "sniffer_egg"]:
        CASES_DATA.append(("head-" + name, 5, name))
    for name in ["stone", "beacon", "sniffer_egg"]:
        CASES_DATA.append(("inventory-" + name, 36, name))
else:
    # SWEEP=2: complete the equippable set (all armor variants, heads, offhand
    # probable items) and re-measure spectral_arrow after the BD9 handshake stall.
    for name in ["spectral_arrow", "filled_map", "nautilus_shell", "ender_pearl",
                 "egg", "snowball", "water_bucket", "book", "clock", "compass",
                 "lead", "name_tag", "saddle", "oak_boat", "chest", "torch",
                 "white_wool", "iron_ingot", "stick", "flint_and_steel",
                 "fishing_rod", "bow", "shears", "brush", "goat_horn", "spyglass"]:
        CASES_DATA.append(("offhand-" + name, 45, name))
    for name in ["netherite_helmet", "leather_helmet", "iron_helmet", "golden_helmet",
                 "chainmail_helmet", "dragon_head", "creeper_head", "zombie_head",
                 "skeleton_skull", "wither_skeleton_skull", "piglin_head",
                 "jack_o_lantern"]:
        CASES_DATA.append(("head-" + name, 5, name))
    for name in ["netherite_chestplate", "elytra", "stone"]:
        CASES_DATA.append(("chest-" + name, 6, name))
    for name in ["netherite_leggings", "stone"]:
        CASES_DATA.append(("legs-" + name, 7, name))
    for name in ["netherite_boots", "stone"]:
        CASES_DATA.append(("boots-" + name, 8, name))


def clean(s):
    return re.sub(r"\x1b\[[0-9;]*m", "", s)


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
    # Explicitly NO control file: production code path, offhand wire slot 1.
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
    print("gateway pid=%d port=%d cases=%d" % (p.pid, PORT, len(CASES_DATA)), flush=True)
    results = []
    try:
        if not wait_port(PORT):
            print("[FATAL] port did not open")
            return 2
        for i, (tag, slot, name) in enumerate(CASES_DATA):
            with open(CASES, "w") as f:
                json.dump([{"tag": tag, "slot": slot, "id": ITEMS[name], "c": 1}], f)
            marker = os.path.getsize(LOG)
            env = dict(os.environ)
            env.update({"MC_PORT": str(PORT), "MC_USER": "BD%d" % i,
                        "MC_VERSION": "1.21.1", "MC_CASES": CASES,
                        "MC_GAP_MS": "100", "MC_TAIL_MS": "1500"})
            try:
                subprocess.run([NODE, "gui-repro.js"], cwd=CLIENT, env=env,
                               stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT,
                               timeout=45)
                timed_out = False
            except subprocess.TimeoutExpired:
                timed_out = True
            time.sleep(0.5)
            with open(LOG, "r", encoding="utf-8", errors="replace") as f:
                f.seek(marker)
                chunk = clean(f.read())
            accepted = len(re.findall(r"accepted item stack request", chunk))
            rejected = re.findall(r"rejected item stack request (-\d+) \(status (\d+)", chunk)
            req_lines = re.findall(r"Creative slot (\d+) -> (\w+)/(\d+) request=(-\d+).*?new=(-?\d+):", chunk)
            errors = len(re.findall(r" /(?:ERROR|SEVERE)|Exception|unhandled", chunk, re.I))
            results.append({"tag": tag, "slot": slot, "item": name,
                            "javaId": ITEMS[name], "accepted": accepted,
                            "rejected": rejected, "timed_out": timed_out,
                            "req": req_lines[:1], "errors": errors})
            print("%-34s slot=%-2d accepted=%d rejected=%s timeout=%s errors=%d"
                  % (tag, slot, accepted, rejected, timed_out, errors), flush=True)
        out = RESULT
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
