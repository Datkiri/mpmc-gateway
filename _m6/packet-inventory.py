# -*- coding: utf-8 -*-
"""Generate docs/M6-PACKET-INVENTORY.md from the patched ViaBedrock source.

Every row is derived from code (enum ids, registered handlers, explicit
dispositions) plus the real -Dmpmc.audit=true gateway log. Nothing is guessed:
rows never observed on the wire are explicitly labelled static-only.
"""
import re
from pathlib import Path

M6 = Path(__file__).resolve().parent
ROOT = M6 / 'gateway-build' / 'ViaBedrock' / 'src' / 'main' / 'java' / 'net' / 'raphimc' / 'viabedrock' / 'protocol'
OUT = M6.parent / 'docs' / 'M6-PACKET-INVENTORY.md'
# Baseline lịch sử: bảng §1–§4 được sinh từ audit động của jar .14 và KHÔNG đổi
# ở .15/.16/.17/.18 (không handler/ID/disposition nào thay đổi). Giữ đường dẫn này
# để tái sinh được đúng bảng gốc. Các mục §5–§9 được append thủ công vào
# docs/M6-PACKET-INVENTORY.md; CHẠY LẠI SCRIPT SẼ GHI ĐÈ và mất các mục đó.
# Regression .18 nằm ở _m6/gw-data/gateway/vp-audit18-reg.log (xem §9).
AUDIT_LOG = M6 / 'gw-data' / 'gateway-audit14' / 'vp-audit14.log'


def strip_comments(text):
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    return '\n'.join(re.sub(r'//.*$', '', line) for line in text.splitlines())


def read(rel):
    return strip_comments((ROOT / rel).read_text(encoding='utf-8'))


# ---- Bedrock packet ids -----------------------------------------------------
ids = {m.group(1): int(m.group(2))
       for m in re.finditer(r'^\s{4}([A-Za-z][A-Za-z0-9_]*)\((\d+)\)',
                            read('data/enums/bedrock/generated/MinecraftPacketIds.java'), re.M)}
cb_enum = [(m.group(1), ids.get(m.group(2)))
           for m in re.finditer(r'^\s{4}([A-Z][A-Z0-9_]*)\(MinecraftPacketIds\.([A-Za-z0-9_]+)\.getValue\(\)\)',
                                read('ClientboundBedrockPackets.java'), re.M)]

# ---- dispositions -----------------------------------------------------------
bp = read('BedrockProtocol.java')
disp = {}
for block in re.finditer(r'putAll\(m,\s*(\w+),\s*(.*?)\);', bp, re.S):
    reason = block.group(1)
    for name in re.findall(r'ClientboundBedrockPackets\.([A-Z0-9_]+)', block.group(2)):
        disp[name] = reason
REASON_TEXT = {
    'JAVA_UNMAPPED': 'java-counterpart-unmapped (có đối tác Java, chưa dịch ngữ nghĩa)',
    'NO_JAVA': 'no-java-packet (Bedrock-only, không có đối tác Java)',
    'CLIENT_UI': 'bedrock-only:client-ui',
    'DEBUG_TOOLING': 'bedrock-only:debug-editor-script',
    'EDUCATION': 'bedrock-only:education-agent',
    'TELEMETRY': 'bedrock-only:telemetry',
}

# ---- handlers / mappings ----------------------------------------------------
mapped_cb, java_cb, cancel_cb, mapped_sb = {}, {}, {}, {}
for path in ROOT.rglob('*.java'):
    text = strip_comments(path.read_text(encoding='utf-8'))
    for m in re.finditer(r'registerClientbound(?:Transition)?\(ClientboundBedrockPackets\.([A-Z0-9_]+)\s*,\s*'
                         r'(?:ClientboundPackets26_3|ClientboundLoginPackets|ClientboundConfigurationPackets26_3)\.([A-Z0-9_]+)',
                         text, re.S):
        java_cb.setdefault(m.group(1), m.group(2))
    for m in re.finditer(r'registerClientbound(?:Transition)?\(ClientboundBedrockPackets\.([A-Z0-9_]+)', text):
        mapped_cb.setdefault(m.group(1), path.name)
    for m in re.finditer(r'cancelClientbound\(ClientboundBedrockPackets\.([A-Z0-9_]+)', text):
        cancel_cb.setdefault(m.group(1), path.name)
    for m in re.finditer(r'registerServerbound\(ServerboundPackets26_3\.([A-Z0-9_]+)\s*,\s*'
                         r'(ServerboundBedrockPackets\.[A-Z0-9_]+|null)', text, re.S):
        mapped_sb.setdefault(m.group(1), (m.group(2), path.name))

java_sb = (M6 / 'java-serverbound-26_3.txt').read_text(encoding='utf-8').split()

# ---- dynamic evidence -------------------------------------------------------
dyn_cb, dyn_sb = set(), set()
if AUDIT_LOG.exists():
    for line in AUDIT_LOG.read_text(encoding='utf-8', errors='replace').splitlines():
        if '[AUDIT]' not in line or ' PRE ' not in line:
            continue
        m = re.search(r'dir=(CLIENTBOUND|SERVERBOUND).*?name=(\S+)', line)
        if not m:
            continue
        (dyn_cb if m.group(1) == 'CLIENTBOUND' else dyn_sb).add(m.group(2))


def level(name, reason, dynamic):
    if reason == 'JAVA_UNMAPPED':
        return '[Chưa xác minh]'
    if dynamic:
        return 'Đã xác minh'
    return 'Đã xác minh (tĩnh)'


def esc(s):
    return str(s).replace('|', '\\|')


lines = []
A = lines.append
A('# Kiểm kê packet Bedrock ↔ Java (mpmc-patched 3.4.14-mpmc.18)')
A('')
A('Tài liệu sinh tự động từ `_m6/packet-inventory.py`. Nguồn: enum `ClientboundBedrockPackets` /')
A('`MinecraftPacketIds` (Bedrock 1.26.51, protocol 2193), các lệnh `registerClientbound*` /')
A('`registerServerbound*` trong fork ViaBedrock, bảng `CLIENTBOUND_DISPOSITIONS`, và log audit động')
A('`-Dmpmc.audit=true` của chính jar `.14` trên BDS thật.')
A('')
A('## Quy ước mức xác minh')
A('')
A('| Mức | Ý nghĩa |')
A('| --- | --- |')
A('| **Đã xác minh** | Có trong log audit động `.14` (packet thật đi qua gateway → BDS/client), và có handler rõ ràng |')
A('| **Đã xác minh (tĩnh)** | Có handler/phân loại trong mã nguồn và audit tĩnh, nhưng chưa xuất hiện trong phiên đo `.14` |')
A('| **[Chưa xác minh]** | `java-counterpart-unmapped`: biết có đối tác Java nhưng chưa kiểm chứng dịch ngữ nghĩa |')
A('| **Mới suy luận** | Không dùng trong tài liệu này; mọi kết luận đều kèm bằng chứng log/mã |')
A('')
A('## Tổng quan đo được trên `.14`')
A('')
A('- Audit tĩnh: **169/169** clientbound Bedrock (92 handler dịch, 21 cancel, 56 classified),')
A('  **69/69** serverbound Java 26.3 (34 dịch sang Bedrock, 35 classify/cancel), `implicit FALLBACK = 0`,')
A('  `duplicate handlers = none`.')
A(f'- Audit động: **9788** dòng `[AUDIT]`, `0` dòng lỗi; `PRE 4827 = POST 2849 + CANCEL 1953 + DEFERRED 25`;')
A('  `POST toHost=true: 1045`, `POST unmapped=true: 0`, `VERDICT: PASS - 100% accounted, zero errors`.')
A(f'- Packet clientbound Bedrock quan sát thật: **{len(dyn_cb)}** tên; serverbound Java quan sát thật: **{len(dyn_sb)}** tên.')
A('- Log: `_m6/gw-data/gateway-audit14/vp-audit14.log`, báo cáo `audit-report14.txt`.')
A('')
A('## 1. Clientbound Bedrock → Java (169)')
A('')
A('| ID (dec/hex) | Packet Bedrock | Xử lý | Đối tác Java / disposition | Mức | Bằng chứng |')
A('| --- | --- | --- | --- | --- | --- |')
for name, num in cb_enum:
    reason = disp.get(name)
    if name in java_cb:
        handling, target = 'dịch (transition)', java_cb[name]
    elif name in mapped_cb:
        handling, target = 'dịch (handler)', mapped_cb[name]
    elif name in cancel_cb:
        handling, target = 'cancel', cancel_cb[name]
    elif reason:
        handling, target = 'classified', REASON_TEXT.get(reason, reason)
    else:
        handling, target = 'FALLBACK (lỗi)', 'chưa đăng ký'
    dynamic = name in dyn_cb
    lvl = level(name, reason, dynamic)
    evid = 'audit động `.14`' if dynamic else 'audit tĩnh `.14`'
    hid = f'{num} / 0x{num:02x}' if num is not None else 'n/a'
    A(f'| {hid} | `{name}` | {handling} | {esc(target)} | {lvl} | {evid} |')
A('')
A('## 2. Serverbound Java 26.3 → Bedrock (69)')
A('')
A('| Packet Java | Đích / xử lý | Mức | Bằng chứng |')
A('| --- | --- | --- | --- |')
for name in java_sb:
    if name in mapped_sb:
        dest, src = mapped_sb[name]
        target = dest.replace('ServerboundBedrockPackets.', 'Bedrock `') + '`' if dest.startswith('ServerboundBedrockPackets.') else f'handler `{src}` (cancel/classify)'
        handling = 'dịch' if dest.startswith('ServerboundBedrockPackets.') else 'handler riêng'
        target = f'{handling}: {target}'
    else:
        target = 'classified/cancel (Bedrock không có đối tác)'
    dynamic = name in dyn_sb
    lvl = 'Đã xác minh' if dynamic else 'Đã xác minh (tĩnh)'
    evid = 'audit động `.14`' if dynamic else 'audit tĩnh `.14`'
    A(f'| `{name}` | {esc(target)} | {lvl} | {evid} |')
A('')
A('## 3. Rủi ro, tái hiện, phòng ngừa và test xác nhận')
A('')
A('| Rủi ro | Nguyên nhân có thể | Cách tái hiện | Phòng ngừa | Test xác nhận |')
A('| --- | --- | --- | --- | --- |')
RISKS = [
 ('Sai ID packet theo state', 'Cùng ID 0x02 là LOGIN success nhưng CONFIGURATION là disconnect; phát lại LOGIN_FINISHED sau khi đã sang CONFIGURATION', 'Broad probe 1.21.4 lặp login (xem `kicked:{"type":117}`)', '`.12`: bỏ synthetic PLAY_STATUS, set CONFIGURATION trực tiếp', 'Bytecode `javap -c` + 36 phiên race, 0 `Chunk size`'),
 ('Sai schema USE_ITEM_ON', '1.21.1 thiếu `worldBorderHit` so với 1.21.4/1.21.6; buffer/typed-value lẫn nhau', 'GUI 1.21.1 click chuột phải', '`InteractionPackets`: `isReadable(VAR_INT,0)` + đọc điều kiện', '`use-item-on-probe.js` 1.21.1, `errors=[]`'),
 ('ACCEPT_TELEPORTATION replay', 'ViaBackwards `Protocol26_3To26_2` passthrough VAR_INT trên buffer đã tiêu thụ (`ridx=1,widx=1`)', 'Internal replay `transformOutgoing`', '`.10/.12`: defensive handler public API + reflection storage', 'Regression 1.21.1/1.21.4/1.21.6, `exit=0`'),
 ('Creative slot không có ack', 'Java 1.21.1 đợi `updateSlot:36`; Bedrock ITEM_STACK_RESPONSE chỉ update tracker nội bộ', '`creative-slot-probe.js` 1.21.1', '`.11`: echo `CONTAINER_SET_CONTENT` đầy đủ', 'Probe nhận `window_items`, `errors=[]`'),
 ('ITEM_STACK_REQUEST status 49', 'GUI gửi 2 `SET_CREATIVE_MODE_SLOT`/click; request 2 dùng source stack chưa có `netId`', '`gui-repro.js` cặp 2 packet cùng slot', '`.13`: hàng đợi per-slot, flush sau ITEM_STACK_RESPONSE', '15/15 accepted, `oldNetId=271…287`'),
 ('ITEM_STACK_REQUEST status 6', 'Hai craft-action chain chồng cùng tick / trạng thái BDS tích lũy', 'GUI 22:17 (20/20); CLI chưa tái hiện', '`.13`: serialize request per-slot; giữ chuẩn CraftCreative→CraftResults→Destroy→Take', '`.14`: 0 status 6; nguyên nhân gốc vẫn [Chưa xác minh]'),
 ('Offhand wire slot sai (status 50/55)', 'BDS 1.26.51 địa chỉ offhand ở `OffhandContainer/1`, tracker ViaBedrock chỉ có slot 0', 'Sweep `OffhandContainer` slot 0/1/4 trên BDS thật', '`.14`: `bedrockRequestSlot()` tách slot wire khỏi slot tracker; normalize response slot 1→0', '`test-offhand-slot1`: slot 1 equip thật (observer `entity_equipment`), slot 0 reject'),
 ('Item không equippable vào Armor/Offhand (status 55)', 'BDS validate component equippable; Java creative cho phép mọi item', 'Sweep 78 case: stone/beacon/sniffer_egg vào slot 5/45', '`.14`: whitelist identifier đo được + `[CLASSIFIED] not sent`, restore slot, echo Java', '`test-mpmc14`: 13 classified, 0 reject, 0 error'),
 ('Thiếu Java→Bedrock mapping / creative entry', '`spectral_arrow` không có runtime item; `filled_map` không có CREATIVE_CONTENT entry', 'Click 2 item trên GUI/CLI: trước `.14` drop im lặng', '`.14`: alias `spectral_arrow→arrow`, `filled_map→empty_map`; không còn drop im lặng', '`echo-probe.js`: `window_items` có item; `silent_drop=0`, `missing_map=0`'),
 ('Connect storm làm BDS chưa gửi START_GAME', '3/78 phiên kẹt CONFIGURATION, thiếu `Server feature version`', '16 kết nối ngắn gap 0.2 s', 'Không sửa gateway (không phải lỗi dịch); test harness dùng gap ≥ 2 s', 'A/B/C: FAST 15/16, SLOW 16/16, CREATIVE-SLOW 20/20; 0 exception'),
 ('Item runtime id âm / custom item', 'Bedrock 1.26 dùng id âm (`-960`, `-213`, `-596`) và blockRuntimeId', 'Sweep id âm + 10 data component', 'Dùng descriptor từ `newItem`, giữ `ItemExtraDataWithoutBlockingTick` 10 byte', 'Sweep 21/21 accepted'),
 ('Component/NBT lệch schema', '`enchantments` / `attribute_modifiers` sai shape gây `SizeOf error for undefined`', 'Encode thử `createPacketBuffer`', 'Probe chỉ dùng 10 component hợp lệ; gateway luôn ghi `has_nbt=false`', 'Encode offline + gửi thật, 0 lỗi serialize'),
 ('Thiếu / trùng / sai thứ tự packet', 'Deferred clientbound tới trước PLAY; replay skip BedrockProtocol', 'Race 36 phiên đồng thời', '`wrapper.send(BedrockProtocol.class, false)`; audit PRE/POST/CANCEL/DEFERRED', '`PRE = POST + CANCEL + DEFERRED`'),
 ('Decode ok nhưng BDS từ chối ngữ nghĩa', 'status 6/11/49/51 không phải lỗi parse', 'Sweep action mode (`.13-exp`)', 'Log status + `restoreCreativeSlot` + queue per-slot', '`.14` 0 rejection'),
 ('Hàng đợi kẹt khi BDS không trả lời', 'Guard in-flight không bao giờ được giải phóng', 'Giả lập mất response', 'Stale timeout 5 s + xoá guard ở mọi early-return + drain loop', 'Edge stress `timed out=0`, 21 request drain hết'),
 ('Giá trị biên: item rỗng / id lạ / burst', 'Drop im lặng hoặc gửi request vô nghĩa', '`gui-edge-cases.json` (burst 5, id 0, id 9999)', 'Early-return có xoá guard; drop có chủ đích', '3 drop đúng, `oldNetId` tăng 289→315, 0 lỗi'),
 ('Version lệch (1.21.1 / 1.21.4 / 1.21.6)', 'Field order / component set / state khác nhau', 'Chạy 3 version cùng kịch bản', 'Handler đọc theo typed-value, không hard-code version', 'Regression 7/7 `exit=0`'),
 ('Rò rỉ GPL vào gói phát hành', 'Nhầm lẫn jar patched vào zip', 'Kiểm tra entry zip', 'Gateway là tiến trình riêng, `packageAgent` chỉ đóng installDist+docs', 'Zip: 0 `viaproxy|viabedrock`, 0 jar, 0 class'),
]
for r in RISKS:
    A('| ' + ' | '.join(esc(x) for x in r) + ' |')
A('')
A('## 4. Ranh giới trung thực')
A('')
A('- 77/169 packet clientbound Bedrock **không có đối tác Java**: được đăng ký, tiêu thụ, phân loại và audit,')
A('  không dịch thành packet Java. Đây là giới hạn giao thức, không phải lỗi dịch.');
A('- 9 packet `java-counterpart-unmapped` vẫn **[Chưa xác minh]** về dịch ngữ nghĩa đầy đủ.')
A('- `status 6` gốc (GUI `JavaReal12`, 22:17) **chưa tái hiện được** bằng CLI trên cùng jar/BDS ⇒ nguyên nhân')
A('  vẫn **[Chưa xác minh]**; `.13` đã loại bỏ cơ chế chồng craft-action chain nhưng không khẳng định triệt tiêu.')
A('- BDS 1.26.51 **từ chối item không equippable** trong `ArmorContainer`/`OffhandContainer` (status 55).')
A('  Java creative cho phép mọi item vào các slot đó ⇒ `.14` phân loại và **không gửi** request, restore slot,')
A('  echo `CONTAINER_SET_CONTENT`. Đây là **[Đã xác minh]** ranh giới ngữ nghĩa BDS (78 case đo trên BDS thật),')
A('  không phải lỗi serialize; người chơi Bedrock khác sẽ không thấy item đó. Whitelist identifier được đo,')
A('  item/add-on Bedrock ngoài vanilla vẫn **[Chưa xác minh]**.')
A('- `spectral_arrow` / `filled_map` được alias sang `minecraft:arrow` / `minecraft:empty_map`:')
A('  **[Đã xác minh]** là hạ cấp có chủ đích (mất hiệu ứng spectral / filled), không phải dịch 1-1.')
A('- 3/78 phiên sweep kẹt ở CONFIGURATION do connect storm 0.2 s (BDS chưa gửi START_GAME):')
A('  **[Đã xác minh]** nằm ngoài tầng dịch packet; với gap ≥ 2 s 0/36 phiên kẹt.')
A('- LAN NetherNet/byulnuri, Java 1.21.7+, BDS 2225, render đồ hoạ đầy đủ: **[Chưa xác minh]**.')
A('- Không đưa số liệu hiệu năng chưa đo. Mọi con số ở đây đều đọc được từ log/mã nguồn.')
A('')

OUT.write_text('\n'.join(lines), encoding='utf-8')
print('wrote', OUT, 'lines', len(lines))
print('clientbound rows', len(cb_enum), 'serverbound rows', len(java_sb))
print('dynamic cb', len(dyn_cb), 'dynamic sb', len(dyn_sb))
