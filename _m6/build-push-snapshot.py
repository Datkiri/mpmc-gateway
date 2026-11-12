"""Build a clean, secret-free snapshot of the mpmc workspace for a GitHub backup repo.

Chạy: python _m6/build-push-snapshot.py
Kết quả: D:/Documents/Muti Player For MCPE/_mpmc-gateway-snapshot
Script cũng được copy vào snapshot (thư mục _m6) để có thể tái dựng.
"""
import shutil
import subprocess
from pathlib import Path

SRC = Path(r"D:\Documents\Muti Player For MCPE\Muti Player For MC JAVA\mpmc")
DST = Path(r"D:\Documents\Muti Player For MCPE\_mpmc-gateway-snapshot")

if DST.exists():
    raise SystemExit("DST đã tồn tại, không ghi đè: %s" % DST)
DST.mkdir(parents=True)

IGN = shutil.ignore_patterns(
    ".git", ".gradle", ".kotlin", "build", "dist", "out", "node_modules", "__pycache__",
    ".workbuddy-ai", "blob_cache", "gw-data", "m2repo", "jars", "logs",
    "*.jar", "*.class", "*.zip", "*.exe", "*.dll", "*.so", "*.bin", "*.db", "*.pyc",
    "*.log", "saves.json", "session.json", "usercache.json", "ops.json",
    "whitelist.json", "banned-ips.json", "banned-players.json", "last-dig.json",
    "*.yml",
)


def tree(rel, dest_rel=None):
    s = SRC / rel
    d = DST / (dest_rel or rel)
    if not s.exists():
        print("MISSING", rel)
        return 0
    d.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(str(s), str(d), ignore=IGN, dirs_exist_ok=True)
    n = sum(1 for p in d.rglob("*") if p.is_file())
    print("TREE  %-45s -> %-40s files=%d" % (rel, dest_rel or rel, n))
    return n


def files(rel_dir, patterns, dest_rel):
    s = SRC / rel_dir
    d = DST / dest_rel
    d.mkdir(parents=True, exist_ok=True)
    n = 0
    for pat in patterns:
        for p in sorted(s.glob(pat)):
            if p.is_file() and p.name not in ("saves.json", "session.json"):
                shutil.copy2(str(p), str(d / p.name))
                n += 1
    print("FILES %-45s -> %-40s n=%d" % (rel_dir, dest_rel, n))
    return n


total = 0
for f in ["README.md", "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
          "gradlew", "gradlew.bat"]:
    p = SRC / f
    if p.exists():
        shutil.copy2(str(p), str(DST / f))
        total += 1

for d in ["gradle", "docs", "agent-core", "agent-cli", "coordinator", "protocol", "relay",
          "transport-iroh", "it", "packaging", "plugins", "m0-spike", "_probe"]:
    total += tree(d)

# gradle-wrapper.jar bị loại bởi pattern *.jar -> copy lại đúng 1 file cần thiết.
w = SRC / "gradle" / "wrapper" / "gradle-wrapper.jar"
if w.exists():
    (DST / "gradle" / "wrapper").mkdir(parents=True, exist_ok=True)
    shutil.copy2(str(w), str(DST / "gradle" / "wrapper" / "gradle-wrapper.jar"))
    total += 1

total += tree("_m5/client")
total += files("_m5", ["*.js", "*.py", "*.sh", "*.md"], "_m5")
total += files("_m6", ["*.py", "*.js", "*.sh", "*.md", "*.txt"], "_m6")
total += files("_m6/gateway-build", ["*.gradle", "*.properties", "*.log", "*.txt"],
               "_m6/gateway-build")
total += tree("_m6/gateway-build/ViaBedrock/src", "gateway-patches/viabedrock-src")

# Diff thật từ 2 fork so với base upstream (có thể git apply lại).
gp = DST / "gateway-patches"
gp.mkdir(parents=True, exist_ok=True)
for repo, base, name in [
    (SRC / "_m6/gateway-build/ViaBedrock", "3756cc0", "viabedrock-mpmc18.patch"),
    (SRC / "_m6/gateway-build/ViaProxy", "1cc5364", "viaproxy-mpmc18.patch"),
]:
    r = subprocess.run(["git", "diff", base + "..HEAD"], cwd=str(repo), capture_output=True)
    (gp / name).write_bytes(r.stdout)
    print("PATCH %-30s bytes=%d rc=%d" % (name, len(r.stdout), r.returncode))

README = """# mpmc-gateway — snapshot công việc 2026-11-12

Repo **private**, tạo mới để lưu toàn bộ trạng thái công việc Java → Bedrock gateway
sau khi repo công khai cũ `Datkiri/bedrock-cross-version-relay` không còn tồn tại trên GitHub.

## Nội dung

| Đường dẫn | Ý nghĩa |
|---|---|
| `agent-core/`, `agent-cli/`, `coordinator/`, `protocol/`, `relay/`, `transport-iroh/`, `it/`, `packaging/`, `plugins/`, `m0-spike/`, `gradle/` | Source Kotlin/Java của agent M0–M6 |
| `docs/` | `M6-BEDROCK.md` (§0 + 1005 dòng), `M6-PACKET-INVENTORY.md` (bản .18), và docs M1–M5, RUNBOOK, MEASUREMENTS |
| `_m5/`, `_m6/` | Probe CLI, script audit, script inventory, build log text, `build-push-snapshot.py` |
| `gateway-patches/viabedrock-src/` | Toàn bộ source Java đã patch của ViaBedrock mpmc.18 |
| `gateway-patches/*.patch` | Diff so với base upstream, `git apply` được |

Không đưa vào repo: fat jar, build output, BDS binary, `node_modules`, log runtime,
`session.json`, `saves.json`, file token. Repo chỉ chứa source/text.

## Pin phát hành

- `PATCHED_VERSION = 3.4.14-mpmc.18`
- `PATCHED_SHA256 = bdeab29f84e9bfa81714da15da49e611438d04e71aa93d088ec0854bdf4f78f8`
- `gateway verify`: `VERIFY_EXIT=0`; zip `mpmc-agent-0.1.0.zip` không chứa `ViaProxy-*.jar`.

## Regression động .18 (đã đo, `vp-audit18-reg.log`)

44.164 dòng audit; PRE 21.988 = POST 19.737 + CANCEL 2.211 + DEFERRED 40;
`toHost=4189`; 0 ERROR / 0 unmapped / 0 unhandled / 0 NPE / 0 PROXY KICK;
creative 50 accepted / 0 rejected; `INTERACT toHost=11`; `USE_ITEM_ON seq=1..80`
(không còn `seq=0`). GUI thật 1.21.1: 26 accepted / 0 rejected, 0 exception.

## Chưa xác minh

Lỗi GUI-only `ItemStackNetResult status 6 / status 60` (log `vp-live-25569.clean.log`)
vẫn **[Chưa xác minh]**: CLI probe không tái hiện được (trade-repro 6/6 accept,
longrun 150 s 47/47 accept). Không đánh dấu PASS.

## Ranh giới GPL / §0

ViaBedrock + ViaProxy đã patch là **tiến trình GPL-3.0 riêng**, KHÔNG nhúng vào
`mpmc-agent-*.zip`. Patch ở đây chỉ để tái dựng bản build; không đưa vào sản phẩm MIT.
Giữ nguyên §0: không sửa byte sau HELLO, không spoof/tắt online-mode, không parse/inject
sau Encryption Response, proxy chỉ bind loopback, không log secret/token, chỉ iroh+iroh-relay,
không TUN/TAP/admin, không copy AGPL/GPL/all-rights-reserved code, không bỏ qua M0.

## Repo liên quan

- `Datkiri/bedrock-cross-version-relay` (private, tạo lại): Go relay xvrelay,
  commit `e3bb416` = native protocol 2225 / Bedrock 1.26.60.
- Base fork: ViaBedrock `3756cc0`, ViaProxy `1cc5364`.
- Commit local: ViaBedrock `8e5162e`, ViaProxy `bd4af0a` (branch `mpmc.18`).
"""

LICENSES = """# License notes

- Mã agent (Kotlin/Java, script, docs) trong repo này là mã nội bộ chưa công khai;
  repo được tạo ở chế độ **private**, chưa gán license công khai.
- `gateway-patches/` chứa source và diff của **ViaBedrock** và **ViaProxy**, các dự án
  **GPL-3.0** của ViaVersionAddons / ViaVersion. Chúng được giữ tách biệt, chỉ phục vụ
  tái dựng bản build; KHÔNG được nhúng vào `mpmc-agent-*.zip` (xem §0 `docs/M6-BEDROCK.md`).
- `third_party/` trong repo relay liên quan có license riêng của từng dự án upstream.
- Không có secret/token nào được đưa vào repo này.
"""

(DST / "PUSH-README.md").write_text(README, encoding="utf-8")
(DST / "LICENSES.md").write_text(LICENSES, encoding="utf-8")
total += 2

size_mb = sum(p.stat().st_size for p in DST.rglob("*") if p.is_file()) / 1048576
print("TOTAL_FILES_COPIED~", total)
print("SNAPSHOT_SIZE_MB", round(size_mb, 2))
