# M6 — Java Edition join server Bedrock

Ngày: 2026-11-10 · Cập nhật: 2026-11-11 (inventory + đặt/đào block (5.2), dịch packet clientbound (5.3), ép CHANGE_DIMENSION trên BDS test riêng (5.4), audit 100% packet 2 chiều + phân loại tường minh (5.5)) · Trạng thái: **đã chạy thật end-to-end trên một máy**

M6 thêm đường Java → Bedrock: người chơi Minecraft **Java** kết nối vào server **Bedrock** (BDS) qua
tunnel mpmc. Tunnel vẫn chỉ chuyển byte thô; phần dịch giao thức nằm ở một tiến trình ViaProxy chạy
**phía host**, bên ngoài gói phát hành mpmc.

## 1. Kiến trúc

```
Minecraft Java client (1.21.11)
  → 127.0.0.1:25566            (join agent, TCP)
  → iroh QUIC stream           (byte thô, direct hoặc relay)
  → host agent 127.0.0.1:25568 (TCP, trỏ tới ViaProxy)
  → ViaProxy 3.4.14            (tiến trình con do mpmc quản lý)
  → RakNet UDP 127.0.0.1:19132 (BDS Bedrock 1.26.51.1)
```

- Join không biết đích thật; nó chỉ nối vào proxy cục bộ. Host quyết định dial ViaProxy, và ViaProxy
  dial server Bedrock. Người join **không chọn được** đích TCP/UDP.
- mpmc không đọc hay sửa gói Minecraft sau `HELLO`; không tắt `online-mode`; không bind ra ngoài
  loopback.
- Cổng do ViaProxy mở mặc định `127.0.0.1:25568`; lớp `BedrockGateway` từ chối mọi bind không phải
  loopback.

## 2. Phiên bản đã kiểm chứng

| Thành phần | Phiên bản | Ghi chú |
|---|---|---|
| ViaProxy | 3.4.14 | Java client 1.7.2–26.3; đích Bedrock trong `--list-versions`: **Bedrock 1.26.51** |
| Gateway mpmc-patched | ViaProxy 3.4.14 + ViaBedrock 0.0.31-mpmc.18 (pin `3.4.14-mpmc.18`, SHA `bdeab29f…`) | build GPL cục bộ, giữ ngoài gói phát hành; cần cho inventory/đặt block (5.2), dịch packet clientbound (5.3), ép `CHANGE_DIMENSION` (5.4), audit 100% packet 2 chiều (5.5), tương thích client Java GUI 1.21.1 (5.6), serialize `ITEM_STACK_REQUEST` per-slot (5.7), phân loại creative item theo container (5.9), clamp `count` creative (5.10), clamp `[1,64]` tập trung (5.11), clamp theo max stack size (5.12, `.17`) và sửa `USE_ITEM_ON` sequence / `INTERACT` / `getEntityByJid` (5.12, `.18`) |
| BDS | 1.26.51.1 (protocol 2193) | `transport=raknet`, UDP 19132 |
| Java client | 1.21.11 (mineflayer) | offline auth, username ≤ 16 ký tự |
| World LAN Bedrock | Minecraft.Windows 1.26.51.01 (protocol 2193) | host tại chỗ, NetherNet qua byulnuri (mục 5.1) |

BDS 1.26.60.6 (protocol 2225) **không** nằm trong danh sách đích của ViaProxy 3.4.14; muốn hỗ trợ phải
nâng ViaProxy và pin SHA-256 mới. Tham khảo xver (MIT) cho bảng protocol 1001/2168/2169/2193/2225 và
ghi chú "banner transport của BDS gây nhầm lẫn — RakNet vẫn hoạt động"; **không** dùng mã nguồn xver.

## 3. Cài đặt gateway

```bash
mpmc gateway install          # tải bản 3.4.14 chính thức từ GitHub, kiểm SHA-256 rồi mới dùng
mpmc gateway install --from <jar>   # cài bản mpmc-patched đã build sẵn trên máy
mpmc gateway status           # in bản cài, đường dẫn, trạng thái hash, java, bind/version mặc định
mpmc gateway verify           # alias của status; trả 0 khi hash khớp, 1 khi sai/thiếu
mpmc doctor --bedrock 127.0.0.1:19132
```

- Tệp nằm ở `<data-dir>/gateway/ViaProxy-3.4.14.jar`; log ở `<data-dir>/gateway/viaproxy.log`.
- SHA-256 pin bản chính thức: `2894bbfb2f4342f8fde887af6c689313efd5b0e46a8006775eea0e21e2948ab5`.
- SHA-256 pin bản mpmc-patched hiện hành: `bdeab29f84e9bfa81714da15da49e611438d04e71aa93d088ec0854bdf4f78f8`
  (bản `3.4.14-mpmc.18`; lịch sử: `0a6f3c80…` = `mpmc.17`, `44bfbe82…` = `mpmc.16`,
  `71ea40ad…` = `mpmc.15`, `1d19a731…` = `mpmc.14`, `62670568…` = `mpmc.13`,
  `b8943ebb…` = `mpmc.12`, `268892d0…` = `mpmc.6`).
  `gateway status` / `gateway verify` in rõ `chính thức` hay `mpmc-patched`; mọi hash
  khác đều bị coi là hỏng.
  Kết quả chạy thật trên bản `.18` (rebuild `./gradlew packageAgent`, giải nén
  `build/dist/mpmc-agent-0.1.0.zip` vào `_m6/release-check18/`, copy jar `.18` vào
  `data/gateway/`, rồi `sh mpmc/bin/mpmc --data-dir data gateway verify`):
  `Trạng thái: SHA-256 khớp (mpmc-patched 3.4.14-mpmc.18)`, mã thoát `0`.
  `unzip -l` của zip mới không có entry `ViaProxy-*.jar` nào — gateway vẫn nằm ngoài
  gói phát hành (§0). Warning `xargs: assertion "bc_ctl.arg_max >= LINE_MAX"` khi chạy
  script là noise đã biết, không fatal. (Số đo `.6` ở các mục lịch sử giữ nguyên.)
- Bản chính thức chỉ đủ để login/spawn/di chuyển cơ bản; **lấy đồ creative và đặt/đào block cần bản
  mpmc-patched** (mục 5.2). Bản patched giữ ngoài `mpmc-agent-*.zip`; xem mục 6 để build lại.
- Nếu tệp sai/hỏng, `gateway status` trả mã 1 và `host --bedrock` từ chối chạy.
- `mpmc host --bedrock` tự gọi `install` khi chưa có (in rõ đang tải), nên có thể bỏ qua bước cài tay.
- ViaProxy cần JDK 17+; mpmc đã yêu cầu JDK 21 nên dùng luôn `java.home` hiện tại.

## 4. Chạy host Bedrock

Dev mode (một máy, không coordinator):

```bash
mpmc host --insecure-dev-ticket --bedrock 127.0.0.1:19132 \
  --bind 127.0.0.1:42002 --control-port 45135 --data-dir _m6/gw-data
```

Production (coordinator như M1–M5, chỉ thêm `--bedrock`):

```bash
mpmc host --bedrock 192.168.1.50:19132 --data-dir /var/lib/mpmc
```

Tuỳ chọn:

| Cờ | Mặc định | Ý nghĩa |
|---|---|---|
| `--bedrock <host:port>` | — | đích Bedrock; thay cho `--target` |
| `--gateway-bind` | `127.0.0.1:25568` | cổng Java của ViaProxy; phải là loopback |
| `--bedrock-version` | `Bedrock 1.26.51` | giá trị phải có trong `ViaProxy cli --list-versions` |

Luồng join **không đổi**: join in `Sẵn sàng: 127.0.0.1:25566`, client Java thêm server đó. Gateway là
tiến trình con; `mpmc disconnect`, `stop` qua cổng điều khiển hoặc Ctrl+C sẽ dừng host rồi dừng gateway
(đã kiểm: sau khi stop, cổng 25568 đóng).

## 5. Kết quả test thật

Chuỗi đã chạy ngày 2026-11-10 trên một máy Windows 11, JDK 21.0.12.1:

1. **Spike thủ công** (ViaProxy chạy riêng): bot Java 1.21.11 vào BDS 1.26.51.1 — login **3036 ms**,
   spawn **3060 ms**, toạ độ `(0.5, -60, 1.27)`, health/food 20.
2. **Qua mpmc tunnel, gateway thủ công**: login **1319 ms**, spawn **1324 ms**, toạ độ
   `(0.5, -60, 3)`, health/food 20. Host log `stream_closed up=73523 down=1274 duration_ms=14303`.
3. **Qua `mpmc host --bedrock` (CLI quản lý gateway)**: login **1393 ms**, spawn **1398 ms**, toạ độ
   `(0.5, -60, 1.92)`, health/food 20. Host log `stream_closed up=70831 down=1041 duration_ms=12368`.
   ViaProxy log ghi đúng phiên:
   `[CONNECT] ... [1.21.11 <-> Bedrock 1.26.51] Connecting to 127.0.0.1:19132` →
   `[SESSION] ... Switching to PLAY state` → `[DISCONNECT]`.

Toạ độ `(0.5, -60, …)` khớp world superflat do BDS tạo theo `level-type=FLAT` (bedrock ở `-64`, mặt đất
`-60`), tức world Bedrock của test chứ không phải world Java ở cổng 25565. Việc bot nhận đúng health/food
20 và giữ phiên 10–14 s chứng minh luồng play state hoạt động, không chỉ login.

Lỗi đã gặp khi test: username 17 ký tự (`BedrockCliGateway`) bị kick
`DecoderException: The received string length is longer than maximum allowed (17 > 16)` — giới hạn của
Java protocol, không phải lỗi tunnel. Đổi sang username ≤ 16 ký tự thì spawn bình thường.

Đo thời gian khởi động gateway (theo log): ViaProxy bắt đầu **20:24:07**, bind cổng **20:24:07**, host
in endpoint **20:24:09** — tức gateway sẵn sàng trong khoảng **2 s** trên máy này.

### 5.1 World LAN của Bedrock (NetherNet) — 2026-11-10

Ngoài BDS qua RakNet, M6 đã thử đích là một world LAN đang mở sẵn trong Minecraft Bedrock
(`My World`, host `ILwHee`, Minecraft.Windows 1.26.51.01 qua levilauncher, protocol 2193).
Dây chuyền giữ nguyên: bot Java → `127.0.0.1:25566` (join mpmc) → iroh → host mpmc → ViaProxy
`127.0.0.1:25568` → **byulnuri-headless** `127.0.0.1:19140` (RakNet → NetherNet) → world LAN.

- byulnuri-headless nằm ở `_m6/byulnuri-src` (bản nghiên cứu, **không** nằm trong gói phát hành
  mpmc). Nó nhận RakNet ở `--listen` rồi dial world LAN qua NetherNet.
- **Bắt buộc: world do chính máy này host chỉ trả lời signaling NetherNet qua IPv6 loopback**
  `[::1]:7551`. Socket discovery phải dual-stack (`[::]:port`) và phải gửi unicast Request tới
  `[::1]:7551`; nếu chỉ broadcast IPv4, world vẫn hiện trong danh sách nhưng lời mời
  `CONNECTREQUEST` bị bỏ qua im lặng (dial timeout 60–120 s). Đã vá trong
  `internal/lansignal/lansignal.go` (bind dual-stack, xếp hạng route `[::1]` cao nhất, ghi nhận
  sender trước khi giao packet) và `internal/relay/relay.go` (`ExtraBroadcasts` = `[::1]:7551`).
  Chẩn đoán nhanh: `lanprobe.exe -world <NetworkID> -timeout 25s` phải in `DIAL OK` / `SPAWN OK`.
- **ViaProxy 3.4.14 + ViaBedrock (lỗi cũ, đã sửa ở `mpmc.3`)**: khi world gửi `ChangeDimension` lúc
  join, ViaBedrock phát `RESPAWN` với gamemode ghi kiểu `BYTE` trong khi remap `26.3 → 26.2` của
  ViaVersion đọc `VAR_INT` + `OPTIONAL_VAR_INT`. Bản `0.0.31-mpmc.3` ghi đúng layout
  (`Types.VAR_INT` cho gamemode, `Types.OPTIONAL_VAR_INT` cho previous gamemode). Cờ
  `--ignore-protocol-translation-errors true` vẫn được truyền như lưới an toàn chung, nhưng không
  còn dùng để che lỗi `RESPAWN` này. **Số đo trên BDS 1.26.51.1 sau khi vá: 0 dòng
  `REMAP OF RESPAWN`, 0 dòng `Unable to read type VarInt`** trong 9 phiên (mục 5.3); đã ép trực tiếp `CHANGE_DIMENSION` trên BDS test riêng (mục 5.4). Đã thử dò world
  LAN lại ở đợt này nhưng không có world nào mở (xem mục 7): **[Chưa xác minh]** trên đích LAN.
- Kết quả đo thật: bot mineflayer 1.21.11 offline (`LanBot`) login **5384 ms**, spawn **5401 ms**,
  toạ độ `(0.5, -61, 0.5)` trong `My World`, health/food 20, giữ phiên 15 s; lần chạy 20 s trước đó
  cho cùng kết quả. Host log phiên 20 s: `stream_closed up=212210 down=1231 duration_ms=26157`.
  Relay log: `relay: dialing LAN world ... remote=[::1]:7551` → `world spawn complete`.
- Danh sách người chơi mà bot nhận được có tên bị lẫn ký tự (world LAN có người thật); đây là lỗi
  hiển thị của lớp dịch, chưa ảnh hưởng spawn — **[Chưa xác minh]** nguyên nhân.
- **Lỗi cũ (đã sửa ở `mpmc.3`)**: lần join đầu của một danh tính mới từng bị kẹt ở `y ≈ 32768`
  (sentinel "chưa có vị trí" của Bedrock) và rơi vô hạn, vì `RESPAWN` sinh từ `ChangeDimension`
  bị remap lỗi rồi bị bỏ; join lại lần hai mới spawn đúng `(0.5, -61, 0.5)`. Workaround "kết nối
  lặp lại" **không còn cần thiết** sau khi sửa layout gamemode (mục 5.3). Trên đích BDS RakNet,
  mọi phiên `mpmc.3` có kiểm tra toạ độ (`verify-*`, `java1216-mpmc3.log`) đều spawn tại `y = -60`
  ngay lần đầu. Trên world LAN NetherNet chưa test lại đợt
  này: **[Chưa xác minh]**. Riêng `CHANGE_DIMENSION → RESPAWN` đã được ép và đo trực tiếp trên BDS
  test riêng (mục 5.4).
- Thử nghiệm hành động thật (bot `ActionBot`, mineflayer 1.21.4): world báo `gameMode: creative`,
  bot spawn trong phòng nhỏ có trần thấp nên phải đào hầm 2 cao mới đi ra. Kết quả một lượt đầy đủ —
  đào 7 block (`grass_block` → `air`), đi **7.4 block**, sprint **7.0 block**, nhảy từ `-61` lên đỉnh
  **-59.75** rồi tiếp đất, xoay người, gửi chat, giữ phiên; `blockAt` trả về block thật.
- **Nguyên nhân gốc của "bot đứng im" (đã sửa)**: mineflayer không gửi packet Java `tick_end`
  (`0x0b`). ViaBedrock chỉ phát `PlayerAuthInput` (Bedrock 144) từ handler `CLIENT_TICK_END`, nên
  trước khi vá, host Bedrock nhận **0 input** — không đi, không nhảy, không sprint, không đào, dù
  physics phía client vẫn chạy và trông như thành công. `_m5/client/bedrock-input.js` bơm một tick
  Java 20 Hz: `player_input` (bitmask `forward/backward/left/right/jump/shift/sprint`) rồi `tick_end`.
  Bài học: `bot.setControlState()` **không** tự sinh `player_input`; pump phải tự ánh xạ control →
  bitmask, nếu không cờ gửi lên luôn rỗng.
- **Sprint** phải đi qua `player_input` (`InputFlag.SPRINT`), không dùng `entity_action
  start_sprinting`: ordinal đó bị ViaVersion đọc thành `PLAYER_COMMAND START_RIDING_JUMP` và
  ViaBedrock kick với `Unhandled PlayerCommandAction`. Wrapper trong `bedrock-input.js` chặn các
  `entity_action` 0–4 để tránh lỗi này.
- Kiểm chứng phía host bằng giải mã tạm thời `PlayerAuthInput` (144) và
  `CorrectPlayerMovePrediction` (161) ngay trong relay (instrumentation đã gỡ sau đo):
  trước khi vá `move=0,0 flags=[]`; sau khi vá `move=0,1 flags=[up]`, sprint
  `[up sprint_down sprinting]`, đào block có `[block_actions]`, nhảy cho correction
  `on_ground=false y=-58.49`. Correction vị trí server bám theo client: đi `x 3.32 → 4.63 → 5.92`,
  lùi `4.56 → 3.25`, delta chỉ `0.12`/gói — host thực sự mô phỏng chuyển động. Vị trí cuối còn lưu
  qua lần join sau (lần kế tiếp spawn đúng `x=0.3`), chứng minh world đã ghi nhận.
- Lưu ý khi điều khiển bot: phải `look` rồi chờ ~0.5–0.8 s trước khi giữ `forward`; Bedrock resync
  vị trí ngay sau `look` và có thể nuốt bước đi đầu tiên.

### 5.2 Inventory creative + đặt/đào block trên BDS 1.26.51 — 2026-11-11

Chuỗi test: bot mineflayer 1.21.4 → gateway `127.0.0.1:25569` (bản mpmc-patched) → BDS 1.26.51.1
`127.0.0.1:19132`, gamemode creative, pump `_m5/client/bedrock-input.js` bật trong suốt phiên.

| Hành vi | Lệnh bot | Kết quả phía BDS | Bằng chứng |
|---|---|---|---|
| Lấy đồ creative | `creative.setInventorySlot(36, stone ×8)` | nhận, lưu vào player data | gateway `Creative slot 36 -> CombinedHotbarAndInventoryContainer/0 request=-1 srcStack=-1 new=1:0x8`; join lại slot 36 vẫn còn stone |
| Đặt block | `placeBlock(grass@2,-61,0, face=+Y)` | block thành `stone@2,-60,0` | join lại `blockAt(2,-60,0)` = `stone` |
| Đào block | `dig(stone@2,-60,0)` | block thành air | join lại `blockAt(2,-60,0)` = `air` |
| Đi bộ | `forward` 1.5 s | di chuyển 6 block | `move before x=0 → after x=6` |
| Nhảy | `jump` giữ 1.4 s | arc `-60 → -58.75 → -60` | `jump-verify.js`, đỉnh −58.75 |
| Sprint | `sprint` + `forward` 1.2 s | 6 block (nhanh hơn đi bộ) | `sprint after x=12` |

Không còn `Packet violation warning`, `PacketMalformed` hay `[PROXY KICK]` trong log gateway của các phiên này.

**Nguyên nhân gốc của "không lấy được đồ / không đặt được block" (đã sửa):**

1. **Creative inventory**: ViaBedrock 0.0.31 chưa gửi `ItemStackRequest` cho thao tác kéo item từ
   creative. Gateway patched gửi `CraftCreative → CraftResultsDeprecated → Take` với
   `request_id = -1` (zigzag32), stack nguồn `CreatedOutputContainer/50`, stack id nguồn = request id,
   và descriptor `ItemV4` extra 10 byte (marker `0` + hai mảng chuỗi rỗng). BDS chấp nhận và trả
   `ItemStackResponse`; slot được áp lại vào container Bedrock kèm `netId`, nhờ đó các transaction sau
   mang đúng netId.
2. **Đặt/đào block**: từ Bedrock **1.26.40**, transaction dùng-item không còn được xử lý như packet
   `INVENTORY_TRANSACTION` (0x1e) đứng riêng — BDS 1.26.51 bỏ qua packet đó. Nó phải nằm trong
   `PlayerAuthInput` (0x90) của tick kế tiếp, kèm input flag `PerformItemInteraction`.
3. **Layout transaction trong `PlayerAuthInput` 1.26.51** (đã xác minh bằng `protocol.json` compile từ
   `minecraft-data` 1.26.51, không dựa vào comment YAML):
   - `transaction?`: 1 byte presence (luôn `1` khi có).
   - `TransactionLegacy`: `legacy_request_id` zigzag32 + `legacy_transactions?` (1 byte presence +
     mảng nếu có).
   - `TransactionActions`: **mảng thường** (`varint` count + từng action), **không** có presence byte
     riêng. Comment trong YAML nói "actions carry their own presence byte" nhưng bản compile không
     sinh byte đó; ghi thêm byte này làm lệch toàn bộ phần sau và BDS trả
     `PacketMalformed: invalid string readNoHeader failed! packetId: 144`.
   - `TransactionUseItem`: **không có** `transaction_type`; action type, trigger, block position,
     face, hotbar slot, hand, `ItemV4` held item, player pos, click pos, block runtime id, dự đoán
     kết quả, cooldown state.
4. **Bằng chứng sai hướng đã loại**: (a) chỉ gửi `TransactionUseItem` trần → `PacketMalformed`;
   (b) gửi nguyên `Transaction` độc lập kèm `transaction_type` → `PacketMalformed`. Chỉ layout ở
   mục 3 được BDS chấp nhận.
5. **Pump tick là bắt buộc**: mineflayer không gửi `tick_end`, nên không có `PlayerAuthInput`; thiếu
   pump thì mọi hành động đều "thành công" phía client mà host Bedrock không thấy gì (đã ghi ở 5.1).

**File đã vá trong fork** (giữ ngoài gói phát hành): `InventoryPackets.java` (creative
`ItemStackRequest` + parse `ItemStackResponse`), `InteractionPackets.java` (`USE_ITEM_ON` → transaction
chờ trong `InventoryTracker`), `ClientPlayerPackets.java` (ghi transaction vào `PlayerAuthInput`),
`InventoryTracker.java` (hàng đợi transaction + dải request id âm lẻ).

### 5.3 Dịch packet clientbound còn lại (bản `0.0.31-mpmc.3`) — 2026-11-11

Mục tiêu của đợt này: không còn packet clientbound Bedrock nào bị drop, bị coi là `unknown`, hay gây
lỗi remap trên đường sang Java. Chuỗi test giữ nguyên: bot mineflayer **1.21.4** → gateway
`127.0.0.1:25569` → BDS 1.26.51.1 `127.0.0.1:19132`, pump `bedrock-input.js` bật suốt phiên.
Jar `be06f517…` (bản `.3`) khớp pin `PATCHED_SHA256` lúc đó; bản hiện hành là `mpmc.6` (mục 5.5).

**Năm lỗi tồn đọng và nguyên nhân gốc (đã sửa):**

1. **Drop packet trước PLAY** — log `vp-bds51-pre-mpmc2.log` có **5** dòng
   `Received packet … outside PLAY state. Ignoring it.` (`LEVEL_EVENT_GENERIC`, `PLAYER_LIST`,
   `SYNC_WORLD_CLOCKS`, `JIGSAW_STRUCTURE_DATA`, `VOXEL_SHAPES`). BDS gửi chúng trước khi client
   Java vào `PLAY`, `BedrockProtocol.transform()` trước đó drop cứng.
   → `DeferredClientboundPackets` (giới hạn 32 768 packet / 64 MB) lưu **payload sau packet id**
   bằng `ByteBufUtil.getBytes(inputBuffer, readerIndex, readableBytes)` rồi cancel; `flush()` đặt ở
   cuối `handleJavaClientGameJoin()`, **sau** `setServerState(State.PLAY)`. Không thêm packet vào
   whitelist trước PLAY, vì chúng cần thứ tự sau `START_GAME`.
2. **Replay đi sai pipeline** — bản `mpmc.2` (`vp-bds51-replay-bug.log`) có **1**
   `Failed to replay deferred packet 124`, **1** `ERROR IN Protocol26_3To26_2 IN REMAP OF
   SYSTEM_CHAT (0x7C)`, **1** `Could not find tag with ID "-110"`. `PacketWrapper.send(Class)` của
   ViaVersion mặc định `skipCurrentPipeline=true`, nên `BedrockProtocol` bị bỏ qua: id Bedrock `124`
   (`0x7c`, `LEVEL_EVENT_GENERIC`) lọt thẳng tới protocol Java và bị đọc nhầm thành `SYSTEM_CHAT`.
   → đổi thành `wrapper.send(BedrockProtocol.class, false)` (đối chiếu
   `ProtocolPipelineImpl.pipes(protocolClass, false, …)` trong nguồn ViaVersion). Bằng chứng đúng
   hướng: phiên `mpmc.3` bot nhận chat `0/1 players sleeping` do chính handler
   `LEVEL_EVENT_GENERIC` / `SleepingPlayers` sinh ra.
3. **`Received unknown packet 341`** — `viaproxy.log` bản cũ có **86** dòng. Bedrock 1.26.51 có
   `locator_bar` (`0x155`) mà enum `ClientboundBedrockPackets` chưa khai báo.
   → thêm `LocatorBarPacket(341)` vào `MinecraftPacketIds.java`, bật `LOCATOR_BAR`, handler mới
   `LocatorBarPackets.java` parse đủ waypoint; client Java ≥ 1.21.6 phát `TRACKED_WAYPOINT`
   (add→0, remove→1, update→2, none→bỏ, style `minecraft:default`, màu RGB từ ARGB), client cũ
   parse rồi bỏ. Cả hai nhánh đã được kiểm chứng: 1.21.4 chạm nhánh "parse rồi bỏ", 1.21.6 chạm
   nhánh `TRACKED_WAYPOINT` (xem **Kiểm chứng 1.21.6** ở dưới).
4. **`Tried to set item for ARMOR, but slot was out of bounds (4)`** — `viaproxy.log` có **40** dòng.
   Container ARMOR Bedrock có 5 slot (Body = 4), Java chỉ có 0..3.
   → `Container.isJavaVisible(int)` mặc định `true`, `ArmorContainer` chỉ hiện 0..3 và
   `getJavaItems()` cắt còn 4; `InventoryPackets.INVENTORY_SLOT` bỏ qua slot Java-invisible thay vì
   set (cursor HUD vẫn giữ).
5. **`REMAP OF RESPAWN` / `Unable to read type VarInt, found ByteType`** — `viaproxy.log` có **114**
   và **57** dòng. `CHANGE_DIMENSION` ghi gamemode sai kiểu.
   → `WorldPackets.java` ghi `Types.VAR_INT` + `Types.OPTIONAL_VAR_INT` (xem mục 5.1).

**Fallback có chủ đích, không phải "dịch":** `BedrockProtocol.registerPackets()` gọi
`cancelClientbound(packet)` cho mọi clientbound chưa đăng ký. Các packet Bedrock-only không có đối
tác Java (`VOXEL_SHAPES`, `JIGSAW_STRUCTURE_DATA`, `SYNC_WORLD_CLOCKS`, …) vì thế được kết thúc sạch
trong pipeline, không rơi ra ngoài và không sinh warning — nhưng chúng **không** được ánh xạ thành
gói Java. Đây là ranh giới thật của cụm "100% gói tin": trong các phiên đo được, **0 packet bị bỏ,
0 packet unknown, 0 lỗi remap**; chưa chứng minh được mọi packet type của mọi phiên bản Bedrock đều
có ánh xạ ngữ nghĩa sang Java — **[Chưa xác minh]**. `--ignore-protocol-translation-errors true`
vẫn được truyền như lưới an toàn chung, không còn dùng để che lỗi `RESPAWN`. **Mục 5.5** đã thay
fallback im lặng này bằng bảng phân loại tường minh + audit từng packet; kết luận ở đây là trạng
thái của bản `.3`, không phải bản hiện hành.

**Bằng chứng log** (cùng thư mục `_m6/gw-data/gateway/`):

| Tệp | Vai trò | Số đo thật |
|---|---|---|
| `viaproxy.log` | bản `mpmc.1`, nhiều phiên cũ | 114 `REMAP OF RESPAWN`, 86 `Received unknown packet 341`, 57 `Unable to read type VarInt`, 40 `Tried to set item for ARMOR`, 114 `outside PLAY state` |
| `vp-bds51-pre-mpmc2.log` | phiên 13:00 trước vá | 5 `outside PLAY state` |
| `vp-bds51-replay-bug.log` | `mpmc.2`, replay sai | 1 `Failed to replay deferred packet 124`, 1 `ERROR IN`, 1 `Could not find tag`; 0 `outside PLAY state` |
| `vp-bds51.log` | **`mpmc.3`, 9 phiên** | 98 dòng, 9 `[CONNECT]`, **12/12 mẫu lỗi = 0** |
| `vp-bds51-err.log` / `vp-test2-err.log` | stderr của `mpmc.4` | 0 byte |
| `vp-bds51-pre-mpmc4.log` / `vp-test2-pre-mpmc4.log` | `mpmc.3`, 9 phiên mỗi gateway trước khi rotate | 12/12 mẫu lỗi = 0; `vp-test2` có 11 `Unhandled command parameter type: CodeBuilderArgs` |
| `vp-bds51.log` | **`mpmc.4`, 3 phiên** | 43 dòng, 3 `[CONNECT]`, **14/14 mẫu lỗi = 0**, 0 `Unhandled command parameter type` |
| `vp-test2.log` | **`mpmc.4`, 1 phiên** | 24 dòng, 1 `[CONNECT]`, **14/14 mẫu lỗi = 0** |
| `java1216-mpmc3.log` | bot Java **1.21.6** | full hành động, spawn `(0,-60,0)`, 0 lỗi |
| `waypoint-probe-mpmc3.log` | 2 bot 1.21.6 đồng thời | mỗi bot 3 `tracked_waypoint` (`track`×1, `update`×2) |

12 mẫu đếm được 0 ở log cuối: `outside PLAY state`, `Received unknown packet`,
`Tried to set item for ARMOR`, `REMAP OF RESPAWN`, `Unable to read type VarInt`,
`Failed to replay deferred packet`, `PacketMalformed`, `Packet violation warning`, `ERROR IN`,
`Could not find tag`, `SEVERE`, `Exception`.

Phiên thứ 6 (`final-check-mpmc3.log`, chạy sau khi build lại CLI) có một `place_error` **phía bot**:
`Server refused to place stone at (2, -60, 0): the block is still stone`. Đây là giả định của script
`verify-actions.js` rằng ô đích đang là air, trong khi phiên `place-mpmc3.log` đã để lại stone ở toạ
độ đó; gateway log vẫn ghi đủ `USE_ITEM_ON` + transaction, và bước `dig` ngay sau đó trả
`air@2,-60,0`. Không phải lỗi dịch packet — đã ghi nhận để không nhầm với regression.

**Kiểm chứng client Java ≥ 1.21.6 (nhánh `TRACKED_WAYPOINT`)** — 2026-11-11, bổ sung sau đợt test đầu:

- `java1216-mpmc3.log`: chạy lại `verify-actions.js` với `MC_VERSION=1.21.6`. Spawn creative
  `(0,-60,0)`, `stone x16`, đặt `stone@2,-60,0`, đào → `air@2,-60,0`, đi `x 0 → 5`, sprint tới
  `x 10`; chat `0/1 players sleeping` vẫn tới. Toàn bộ kịch bản như client 1.21.4.
- `waypoint-probe-mpmc3.log`: script mới `_m5/client/waypoint-probe.js` mở **2 bot 1.21.6 cùng lúc**
  (`WpA`, `WpB`, 26 s), bơm tick, cho hai bot đi ngược nhau, và **chỉ nghe** packet phía Java
  (không inject byte). Kết quả mỗi bot nhận **3** gói `tracked_waypoint`: `track`×1 + `update`×2.
  Đây là bằng chứng wire-level rằng `locator_bar` (Bedrock 341 / `0x155`) đã đi qua handler và
  phát đúng `TRACKED_WAYPOINT` (Java 1.21.6 `0x83`), không chỉ "parse rồi bỏ".
- Lưu ý cách đọc log: `waypoint-probe.js` in `y: 0` tại sự kiện `spawn` vì mineflayer bắn `spawn`
  trước khi nhận gói vị trí; script không gọi `waitSane()`. Các script có kiểm tra toạ độ
  (`verify-actions.js`, `inv-check.js`, …) đều báo `-60`. Không phải lỗi `y ≈ 32768`.
- Đối chiếu schema: `minecraft-data/bedrock/1.26.51/protocol.json` → `341 = locator_bar`;
  `minecraft-data/pc/1.21.6/protocol.json` → `0x83 = tracked_waypoint`.

**Test suite mpmc** (chạy lại không bỏ qua test, `./gradlew test --offline`): **14 suite / 72 test /
0 failure / 0 error / 0 skipped**, `BUILD SUCCESSFUL` trong 1m45s (đếm từ `TEST-*.xml`). Trong đó
`BedrockGatewayTest` (8 test) kiểm pin/hash/bind của bản `mpmc.4`, `TunnelIT` (6),
`AuthIT` (7), `CoordinatorIT` (10), `RelayIT` (2), `CliProcessIT` (3) đều pass. Các bản build ở mục
6 dùng `-x test` chỉ để rút ngắn vòng lặp; lần chạy đầy đủ này xác nhận không có regression.

**Kết quả hành động trên `mpmc.3`** (log bot cùng thư mục):

- `verify-mpmc3.log`: chat `0/1 players sleeping`; spawn creative `(0,-60,0)`; `stone x16` vào slot
  36; đặt `stone@2,-60,0`; đào → `air@2,-60,0`; đi `x 0 → 5`; sprint tới `x 10`.
- `jump-mpmc3.log`: 15 mẫu cung nhảy, đỉnh `-58.69`, tiếp đất `-60`.
- `place-mpmc3.log`: `stone x8`, đặt `stone@2,-60,0`; bước dig của script trỏ `2,-60,1` đang là air
  nên bị skip (đã đào ở kịch bản `verify-mpmc3`).
- Relog `relog-inv-mpmc3.log`: `held: stone x8`, slot 36 `stone x8`.
- Relog `relog-block-mpmc3.log`: `2,-60,0=stone`.

**File vá thêm trong fork** (ngoài danh sách mục 5.2): `Container.java`, `ArmorContainer.java`,
`InventoryPackets.java`, `WorldPackets.java`, `MinecraftPacketIds.java`,
`ClientboundBedrockPackets.java`, `LocatorBarPackets.java` (mới),
`DeferredClientboundPackets.java` (mới), `BedrockProtocol.java`, `JoinPackets.java`.

**Build/pin:** `ViaBedrock/gradle.properties` → `maven_version=0.0.31-mpmc.4`;
`ViaProxy/build.gradle` → `net.raphimc:ViaBedrock:0.0.31-mpmc.4`. Cả hai `gradle.properties` đặt
`systemProp.maven.repo.local` trỏ `_m6/gateway-build/m2repo` trên ổ **D**, để
`publishToMavenLocal` không ghi artefact build mới vào `C:\Users\Acer\.m2`.

### 5.4 Ép `CHANGE_DIMENSION → RESPAWN` trên BDS test riêng + dọn WARNING cuối (bản `0.0.31-mpmc.4`) — 2026-11-11

Bàn giao còn một edge case chưa đo được: sửa layout `RESPAWN` mới chỉ được suy từ việc log hết lỗi,
chưa từng ép BDS thật sự gửi `CHANGE_DIMENSION`. Đợt này tự dựng một BDS **riêng** để ép nó, không
đụng BDS gốc (PID `18420`, UDP `19132`) hay world save gốc.

| Thành phần | Giá trị |
|---|---|
| BDS test2 | copy `_m6/bds51` → `_m6/bds-test2`, UDP `19142` / `19143`, PID `7748` |
| World | `level-name=dimtest`, `level-type=FLAT`, `gamemode=creative`, `allow-cheats=true`, `default-player-permission-level=operator` |
| Gateway test2 | `127.0.0.1:25570` → `127.0.0.1:19142`, jar `.4` SHA `17f5944b…`, log `vp-test2.log` |
| BDS gốc / gateway gốc | `18420` / `19080` (`.4`, `127.0.0.1:25569` → `19132`) — giữ nguyên |

Banner `TRANSPORT TYPE ERROR ... NetherNet is the only supported transport type` của BDS 1.26.51.1
vẫn xuất hiện nhưng cổng RakNet gameplay `19142` vẫn nhận player (đã ghi ở mục 2), không đổi cấu
hình transport.

**Cách ép đổi chiều (không đi bộ)** — `_m5/client/dimension-probe.js` dùng `chat_command` Java
**không có dấu `/`** (ViaBedrock tự prepend trong `ChatPackets.java`), nên tránh luôn việc Git
Bash/MSYS biến `/list` thành đường dẫn Windows. Ba điểm sửa so với bản probe đầu:

1. `WP_PRE_DELAY_MS=2500`: BDS chỉ đăng ký player vào command context sau dòng `Player Spawned`;
   lệnh gửi ngay sau sự kiện `spawn` của mineflayer luôn trả `No targets matched selector`, kể cả
   `@s` (đã đo ở `dim-death1.log`, `dim-void1.log`; sau khi thêm delay, `tp @s` chạy ngay).
2. Không cho bot đi bộ vào portal (yaw không tin cậy — lần đầu bot đi `+Z` tới `z=9.27` trong khi
   portal ở `x=3..4, z=0`, nên vẫn `overworld`). Thay bằng `setblock … portal` + `tp @s …` để đặt
   thẳng player vào block portal.
3. `WP_COMMANDS` cho phép chuỗi 4 lệnh để đi **2 chiều** trong một phiên:
   `setblock 0 -60 0 portal|tp @s 0 -60 0|setblock 16 41 0 portal|tp @s 16 41 0`.

**Kết quả đo được**

| Phiên | Client | `respawn` Java | Diễn biến |
|---|---|---|---|
| `dim-portal1.log` (`.3`) | 1.21.4 | 1 | `overworld → the_nether`; Nether nạp `map_chunk 114`, `block_change 2016` |
| `dim-portal216.log` (`.3`) | **1.21.6** | 1 | `overworld → the_nether`, cùng schema `worldState` + `copyMetadata` |
| `dim-round.log` (`.3`) | 1.21.4 | **2** | `overworld → the_nether → overworld` trong cùng phiên |
| `dim-round-mpmc4.log` (`.4`) | 1.21.4 | **2** | `overworld → the_nether → overworld`, `dimensionChanged:true`, `stillConnected:true` |

Mọi gói `respawn` đều có đúng schema Java (`worldState`, `copyMetadata`), mineflayer cập nhật
`bot.game.dimension`, và gateway **không** phát `REMAP OF RESPAWN` / `Unable to read type VarInt`.
Đây là bằng chứng trực tiếp cho fix `Types.VAR_INT` + `Types.OPTIONAL_VAR_INT` trong
`WorldPackets.java`, không chỉ là "log hết lỗi".

**Death / respawn trong cùng dimension**: `kill @s` bị BDS từ chối đúng luật (`Players cannot be
killed while they are in Creative mode`, do `force-gamemode=true`). Ép `gamemode survival @s|kill @s`
thì BDS báo `DimDeath4 died` / `Killed DimDeath4`, bot vẫn kết nối, **0 lỗi gateway**, nhưng
**không** có gói `respawn` Java vì BDS không gửi `CHANGE_DIMENSION` khi chết trong cùng dimension.
Phủ sóng ngữ nghĩa màn hình chết / respawn của client Java: **[Chưa xác minh]**.

**Dọn WARNING cuối (`mpmc.4`)**: log `.3` còn **11** dòng
`Unhandled command parameter type: CodeBuilderArgs` (1 mỗi lần join) do Bedrock 1.26.51 thêm
tham số lệnh `CodeBuilderArgs(0x10005A)` trong `CommandRegistry_HardNonTerminal`. Nhánh `default`
của `CommandsStorage.java` đã xử lý đúng bằng `StringArgumentType.greedyString()`, chỉ thiếu case
tường minh. Bản `.4` thêm `case CodeBuilderArgs` với **cùng biểu thức**
`argument(name + ": unknown", greedyString())` để cây lệnh `declare_commands` gửi cho Java
**không đổi một byte**, chỉ bỏ log nhiễu. Số đo sau vá: `Unhandled command parameter type` = **0**,
`Invalid command parameter` = **0** trên cả 2 gateway.

**Log `.4` sau 4 phiên** (`vp-bds51.log` 3 phiên / 43 dòng, `vp-test2.log` 1 phiên / 24 dòng):
**14/14 mẫu** = 0 (`outside PLAY`, `Received unknown packet`, `Tried to set item for ARMOR`,
`REMAP OF RESPAWN`, `Unable to read type VarInt`, `Failed to replay deferred packet`,
`PacketMalformed`, `Packet violation warning`, `ERROR IN`, `Could not find tag`, `SEVERE`,
`Exception`, `Unhandled command parameter type`, `Invalid command parameter`), stderr 0 byte.
Còn 4 dòng WARNING **không phải lỗi**: 2 thông tin `There is a newer plugin version available`
(ViaVersion, mỗi gateway 1 lần lúc khởi động) và 2 `Received non-zero VARIANT 3 for unsupported
entity minecraft:trader_llama` (ViaBedrock, không gây exception/kick/rớt gói). Ngữ nghĩa variant
của entity này: **[Chưa xác minh]**.

**Hành động / waypoint / test suite trên `.4`**: `final-check-mpmc4.log` spawn `y=-60`, `stone x16`,
đặt `stone@2,-60,0`, đào → `air`, đi 5 block, sprint tới `x=10`; `waypoint-mpmc4.log` 2 bot 1.21.6
mỗi bot nhận 1 `tracked_waypoint` (`track`) — ít hơn 3 gói của `.3` vì lần này bot không phát sinh
`update`, không phải mất gói. `./gradlew test --offline`: **14 suite / 72 test / 0 failure /
0 error / 0 skipped**, `BUILD SUCCESSFUL in 1m 45s` (đếm từ `TEST-*.xml`), trong đó
`BedrockGatewayTest` (8) pass với pin `.4`. `gateway verify` từ gói phát hành đã giải nén:
`Trạng thái: SHA-256 khớp (mpmc-patched 3.4.14-mpmc.4)`, mã thoát `0`.

**Build/pin `.4`**: `ViaBedrock/gradle.properties` → `maven_version=0.0.31-mpmc.4`,
`ViaProxy/build.gradle` → `net.raphimc:ViaBedrock:0.0.31-mpmc.4`; `publishToMavenLocal` 22 s,
ViaProxy `build` 46 s, đều `BUILD SUCCESSFUL`. Jar `17f5944b…` deploy vào cả
`gw-data/gateway/` lẫn `gw-data/gateway-test2/`; bản `.3` lưu tại `*.jar.prev13`. Fat jar vẫn chứa
`LocatorBarPackets.class`, `DeferredClientboundPackets.class`, `CommandsStorage.class`.

### 5.5 Audit 100% packet 2 chiều + phân loại tường minh (bản `0.0.31-mpmc.6`) — 2026-11-11

**Vì sao phải làm tiếp sau `.4`**: log `.4` sạch 14/14 mẫu lỗi, nhưng sạch không đồng nghĩa "dịch
hết". `BedrockProtocol.registerPackets()` còn một vòng fallback `cancelClientbound(packet)` huỷ
im lặng mọi packet clientbound không có handler — không có bằng chứng nào cho từng packet. Script
audit tĩnh `_m6/packet-audit.py` (đã sửa để **bỏ comment** trước khi đếm; bản đầu đếm nhầm 4 dòng
`//protocol.cancelClientbound(...)` thành cancel thật, nên `CAMERA_SPLINE` tưởng đã xử lý nhưng
runtime vẫn rơi fallback) cho kết quả trên `.4`: **169 clientbound = 92 handler + 21 cancel tường
minh + 56 fallback**, và chiều Java→Bedrock có **69 `ServerboundPackets26_3` = 34 handler + 35
cancel im lặng**.

**`.5` — audit động**: thêm cờ `-Dmpmc.audit=true` (tắt mặc định trong production). `transform()`
ghi `PRE` (id/name/bytes/`hasHandler`), `POST` (`mappedJavaId` ở clientbound; `toHost=true` +
`outName` ở serverbound), `CANCEL`, `ERROR`, `DEFERRED`, `CLASSIFIED`. Mọi thao tác audit nằm trong
`try/catch` nên không thể ảnh hưởng xử lý packet. `PRE` lấy `id` **trước** `super.transform()`: nếu
dùng id sau transform (đã đổi sang id Java hoặc `-1` khi cancel) sẽ tra nhầm vào bảng Bedrock và
sinh nhãn `UNKNOWN_BEDROCK_ID_*` giả. Tên packet Java cũng tra theo `state` (LOGIN/CONFIGURATION
dùng enum riêng), không dùng enum PLAY cho mọi trạng thái.

**`.6` — bảng phân loại tường minh**: thay fallback mù bằng `CLIENTBOUND_DISPOSITIONS`
(`EnumMap` 56 entry) và `classifyClientbound()` / `classifyServerbound()` — mỗi packet không có đối
tác Java vẫn bị huỷ sạch như cũ, nhưng có **một dòng audit kèm lý do**. Lý do gồm:
`no-java-packet`, `bedrock-only:debug-editor-script`, `bedrock-only:client-ui`,
`bedrock-only:education-agent`, `bedrock-only:telemetry`, và `java-counterpart-unmapped` (9 packet
mà Java có đối tác danh nghĩa nhưng ViaBedrock không có ánh xạ wire/registry an toàn — phát gói
đoán sẽ tạo packet Java malformed, nên tiêu thụ rồi kết thúc sạch). Nếu còn packet nào sót, gateway
log `SEVERE ... UNCLASSIFIED` ngay lúc khởi động — đã kiểm chứng **không có dòng nào**. Audit tĩnh
sau `.6`: **169/169 clientbound** (92 dịch + 21 cancel + 56 phân loại, **0 fallback**),
**69/69 Java serverbound** (34 dịch + 35 phân loại).

**Bài test**: `_m5/client/packet-audit-probe.js` — bot mineflayer ghi nhận tên packet clientbound
thực nhận và tên packet serverbound thực gửi, thực hiện 15 hành động (chat, hotbar, creative slot,
look, swing, place, dig, đi/nhảy/sneak/sprint, toss, tab-complete, ping, stats, close window) rồi
gửi 31 lệnh BDS (time/weather/gamerule/difficulty/effect/xp/summon/particle/playsound/title/
tellraw/me/tp/setblock/fill/enchant/recipe/spawnpoint/scriptevent/clear/list). Không inject byte
Minecraft thô. `_m6/audit-report.py` phân tích log `[AUDIT]`.

| Đối tượng | Kết quả |
|---|---|
| Phiên runtime | 6 (1.21.4 test2, 1.21.6 test2, 1.21.4 gateway chính, dimension regression, waypoint 2 bot, …) |
| Dòng audit | 13.803 (test2) + 10.380 (main) = **24.183** |
| Cân bằng phase | **PRE 11.974 = POST 6.923 + CANCEL 5.006 + DEFERRED 45** (khớp tuyệt đối) |
| Clientbound Bedrock quan sát | **74 loại**, `hasHandler=false` = 0, `UNKNOWN_BEDROCK_ID` = 0 |
| Serverbound Java quan sát | **26 loại**, `POST toHost=true` = **2.607**, `unmapped=true` = **0** |
| Gói Bedrock ghi ra host | 9 tên: `COMMAND_REQUEST` 93, `PLAYER_AUTH_INPUT` 1.484, `TEXT` 3, `MOB_EQUIPMENT` 6, `ANIMATE` 3, `CONTAINER_CLOSE` 3, `REQUEST_CHUNK_RADIUS` 3, `REQUEST_NETWORK_SETTINGS` 3, `RESOURCE_PACK_CLIENT_RESPONSE` 3 |
| `CLASSIFIED` | 235 dòng, kèm lý do cho từng packet |
| Lỗi | `ERROR` phase = 0, `error=true` = 0, dòng lỗi ngoài audit = 0, stderr = 0 byte |
| `audit-report.py` | **VERDICT: PASS — 100% accounted, zero errors** |

**Phía client Java hợp lệ**: 3 phiên probe đều `exit=0`, `errors=[]`, `stillConnected=true`; client
parse được **48** (1.21.4/test2), **47** (1.21.6/test2), **41** (1.21.4/main) tên packet clientbound
khác nhau, trong đó có `spawn_entity_experience_orb`, `set_title_text`, `sound_effect`, `world_event`,
`entity_effect`, `registry_data`, `respawn`… Không có lỗi parse, không rớt kết nối.

**Phía host Bedrock hợp lệ**: BDS thực thi lệnh và trả kết quả qua chat (`Set the time to 265000`,
`Changing to rainy weather`, `Gave Speed * 1 …`, `Gave 10 experience …`,
`Object successfully summoned`, `Played sound …`, `Title command successfully executed`,
`You have been teleported to …`, `Block placed`). BDS test2 ghi **17 connect / 17 disconnect** cân
bằng, **0 `PacketViolationWarning`**, 0 malformed, 0 kick; banner `TRANSPORT TYPE ERROR …
NetherNet is the only supported transport type` là banner gây nhầm lẫn đã biết (RakNet vẫn nhận).
Regression `.6`: `dimension-probe.js` vẫn nhận `respawn` → `the_nether`, `dimensionChanged:true`,
`stillConnected:true`; `waypoint-probe.js` 2 bot 1.21.6 mỗi bot nhận `track` + `update`.

**Ranh giới trung thực**: 77/169 packet clientbound Bedrock (21 cancel + 56 phân loại) **không có
đối tác Java**, nên không thể "dịch thành gói Java" theo nghĩa đen — Java không định nghĩa gói đó.
Chúng được **đăng ký tường minh, tiêu thụ và kết thúc sạch**, không bị rơi im lặng. Điều đạt được là
**100% packet được quyết định/xử lý có bằng chứng, 0 lỗi, 0 gói malformed ở cả 2 đầu**; không phải
"100% packet đều trở thành một gói Java tương ứng". 9 packet nhóm
`java-counterpart-unmapped` (`CONTAINER_SET_DATA`, `CRAFTING_DATA`, `SPAWN_EXPERIENCE_ORB`,
`MAP_ITEM_DATA`, `UPDATE_TRADE`, `UPDATE_EQUIP`, `PLAYER_START_ITEM_COOLDOWN`,
`UNLOCKED_RECIPES`, `TRIM_DATA`) có đối tác danh nghĩa nhưng thiếu ánh xạ item/entity/container id
trong ViaBedrock; muốn dịch thật cần bổ sung ánh xạ registry và test riêng — **[Chưa xác minh]**.
Phủ sóng mọi packet type trên phiên bản Bedrock khác / client Java khác: **[Chưa xác minh]**.

**Build/pin `.6`**: `ViaBedrock/gradle.properties` → `maven_version=0.0.31-mpmc.6`,
`ViaProxy/build.gradle` → `net.raphimc:ViaBedrock:0.0.31-mpmc.6`; `publishToMavenLocal` 23 s,
ViaProxy `build` 41 s, đều `BUILD SUCCESSFUL`. Jar `268892d0…` deploy vào cả
`gw-data/gateway/` lẫn `gw-data/gateway-test2/`; bản `.5` lưu tại `*.jar.prev15`, bản `.4` tại
`*.jar.prev14`. Fat jar chứa `buildDispositions`, `classifyClientbound`, `classifyServerbound`,
`javaClientboundName`, `javaServerboundName`, `LocatorBarPackets.class`,
`DeferredClientboundPackets.class`, `CommandsStorage.class`.

**Kiểm chứng cấu hình production (audit TẮT)**: sau khi chốt `.6`, cả 2 gateway được restart
**không có** `-Dmpmc.audit=true` (`127.0.0.1:25569` → BDS `19132`, `127.0.0.1:25570` → BDS test2
`19142`), jar vẫn `268892d0…`, bind đúng cổng, stderr **0 byte**. Quét 13 mẫu (`[AUDIT]`,
`ERROR IN`, `Exception`, `PacketMalformed`, `Packet violation`, `Failed to replay deferred packet`,
`outside PLAY state`, `Received unknown packet`, `Unable to read type VarInt`, `REMAP OF RESPAWN`,
`UNCLASSIFIED`, `SEVERE`, `WARN … Unhandled`) cho **0/0** trên cả 2 log ⇒ tắt flag không đổi hành vi
và không phát log audit trong production. `verify-actions.js` trên cả 2 luồng `exit=0`: lấy đồ
`stone x16`, đặt `air → stone`, đào `stone → air`, đi 6 / 5 block, sprint; chat
`0/1 players sleeping` chứng minh deferred replay vẫn chạy khi audit tắt. Probe rộng production
`errors=[]`, `stillConnected=true` (1.21.4/main **40** clientbound + **17** serverbound;
1.21.6/test2 **45** + **17**); host test2 thực thi đầy đủ lệnh (time/weather/gamerule/difficulty/
effect/xp/summon/particle/playsound/title/tp/setblock…). BDS chính từ chối một số lệnh với
`Incorrect permission level` do cấu hình quyền host, **không phải lỗi dịch**: BDS vẫn trả
`system_chat` Java hợp lệ. Regression production: `dimension-probe.js` → `the_nether`,
`dimensionChanged:true`, `stillConnected:true`; `waypoint-probe.js` 2 bot 1.21.6 mỗi bot nhận
`track` + `update`. Hai câu lệnh sai cú pháp BDS trong kịch bản thử (`give @s minecraft:map 1`,
`subtitle …`) đã được gỡ khỏi `packet-audit-probe.js`; chạy lại cả 2 luồng cho **0**
`Syntax error` / `Unknown command`, `exit=0`.

### 5.6 Client Java GUI 1.21.1 thật + sửa tương thích `.7`–`.12` — 2026-11-11

Sau khi chạy client Java **GUI thật** 1.21.1 (`launch-real-client.js`, Quick Play
`127.0.0.1:25569`), người dùng báo `Có lỗi`. Log client ghi
`Packet Type: USE_ITEM_ON ... Unable to read type Unsigned Byte, found VarInt`. Bốn lỗi độc lập
được tách ra, mỗi lỗi có probe tái hiện tối thiểu riêng:

| Bản | Triệu chứng | Nguyên nhân gốc | Cách sửa |
|---|---|---|---|
| `.7` | `USE_ITEM_ON` (0x42) kick 1.21.1 | `BlockItemPacketRewriter1_21_2` biến gói thành `PacketValue` có kiểu; `InteractionPackets` đọc `UNSIGNED_BYTE` face → `pollReadableObject` gặp `VarInt` | `isReadable(VAR_INT, 0)` nhận diện dạng typed value; đọc face bằng `VAR_INT`, chỉ đọc `worldBorderHit`/`sequence` khi thật sự có |
| `.10` | `ACCEPT_TELEPORTATION` (0x00): `readerIndex(1)+1 > writerIndex(1)` | ViaBackwards `Protocol26_3To26_2` gọi `passthrough(VAR_INT)` trên replay nội bộ đã tiêu thụ buffer | đăng ký lại handler phòng thủ trên chính instance protocol bằng public API ViaVersion; chỉ đọc khi có typed value/byte, `ClientPlayerPackets` cũng phòng thủ |
| `.11` | 1.21.1 `updateSlot:36` timeout | client 1.21–1.21.1 chờ ack server sau `SET_CREATIVE_MODE_SLOT`; `ITEM_STACK_RESPONSE` chỉ cập nhật tracker nội bộ | echo `CONTAINER_SET_CONTENT` đầy đủ sau optimistic update (cùng cơ chế `restoreCreativeSlot`) |
| `.12` | `kicked:{"type":117}` / `Chunk size is 26 but only 2 was read` (chập chờn) | xem phần dưới | bootstrap thẳng state Bedrock, không replay `PLAY_STATUS` tổng hợp |

**Root cause `.12`, đo được tới từng byte.** Ở phiên probe rộng bị lỗi, `clientboundNames` có thêm
`disconnect`, log gateway có đúng **1** lần `Skipping LOGIN state` và **2** lần
`Connected successfully! Switching to CONFIGURATION state` cho cùng một kết nối. Client log:

```
Chunk size is 26 but only 2 was read ; partial packet : {"name":"disconnect","params":{"reason":{"type":117}}}; buffer :02754636422dd538a1aec1e2da46e98d450742726f6164313100
```

Giải mã buffer: `02` = packet id; `75463642-2dd5-38a1-aec1-e2da46e98d45` = UUID offline;
`07` + `Broad11` = username; `00` = mảng properties rỗng ⇒ đúng payload **Java `LOGIN_FINISHED`**.
Với Java 1.21.4, `LOGIN` id `0x02` = `success`, còn **`CONFIGURATION` id `0x02` = `disconnect`**
(reason kiểu NBT). Byte đầu UUID `0x75 = 117` bị đọc thành NBT tag type không hợp lệ ⇒
`{"type":117}` ⇒ mineflayer emit `kicked` (kết nối thực tế vẫn `stillConnected=true`). Nguồn phát
là nhánh **upstream** `BedrockProtocol.transform()` (`Skipping LOGIN state`, xác nhận có trong
`HEAD` của fork): khi BDS gửi packet thuộc `BEFORE_PLAY_STATE_WHITELIST` lúc
`serverState == LOGIN`, nhánh này tạo `PLAY_STATUS(LoginSuccess)` tổng hợp rồi
`send(BedrockProtocol.class, false)` ngược pipeline. Dispatcher của
`StatelessTransitionProtocol` chọn handler theo `getServerState()` = LOGIN ⇒ handler LOGIN phát
thêm một `LOGIN_FINISHED` thứ hai, trong khi ViaProxy đã hoàn tất LOGIN Java từ trước. Đây là race
thứ tự gói BDS nên chỉ xuất hiện chập chờn.

**Sửa.** Thay đoạn tạo/gửi `PLAY_STATUS` tổng hợp bằng bootstrap trực tiếp:
`setServerState(CONFIGURATION)` + `JoinPackets.sendClientCacheStatus(user)` (đổi method thành
`public`). Nhánh này giờ **không phát packet Java nào**. Bằng chứng cấu trúc: `javap -c` trên class
trong jar `.12` cho nhánh chỉ còn `setServerState` + `sendClientCacheStatus`, **không** còn
`PacketWrapper.create(...PLAY_STATUS...)` hay `send()`. `PLAY_STATUS(LoginSuccess)` thật từ BDS
(nếu tới sau) lúc này rơi vào handler `CONFIGURATION` (cancel + cache status), cũng không phát
`LOGIN_FINISHED`.

**Kiểm chứng `.12`.** `publishToMavenLocal` 25 s + ViaProxy `build` 1 m 03 s, đều
`BUILD SUCCESSFUL`; fat jar SHA
`b8943ebb80f02b444b1a3d09436c6845f1c52763c19c197ca7b0f5ca5cfd49c1` deploy cả 2 gateway, restart
production **không có** `-Dmpmc.audit=true`, stderr **0 byte**.

- Audit tĩnh: `169/169` clientbound + `69/69` serverbound, `implicit FALLBACK = 0`,
  `duplicate handlers = none`.
- Audit động (`-Dmpmc.audit=true`) trên chính jar `.12` với 1.21.1 / 1.21.4 / 1.21.6:
  **24.565 dòng audit, 0 dòng lỗi**; `PRE 12.200 = POST 9.880 + CANCEL 2.305 + DEFERRED 15`,
  `POST unmapped=true: 0`, `ERROR phase = 0`,
  `VERDICT: PASS - 100% accounted, zero errors`.
- Regression **6/6 `exit=0`** trên 25570: `verify-actions.js` 1.21.1 / 1.21.4 / 1.21.6,
  `use-item-on-probe.js` 1.21.1, `creative-slot-probe.js` 1.21.1, probe rộng 1.21.4 — không
  `kicked`, không `disconnect`, không `Chunk size`.
- Săn race: **36 phiên** trên 25569 (24 ngắn + 12 đầy đủ, có đợt 3 kết nối đồng thời),
  36/36 `exit=0`, `kicked=0`, `raw_disconnect=0`, `disconnect=0`, `Chunk size=0`; gateway
  `36 connect / 36 disconnect`, `0` mẫu lỗi, stderr `0 byte`; BDS chính lẫn BDS test2
  `0 PacketViolationWarning`. `Skipping LOGIN state` **không tái hiện** trong 36 phiên này, nên
  không ép được màn "trước/sau" runtime của chính nhánh đó; bằng chứng thay thế là bytecode đã xoá
  hoàn toàn đường phát packet Java — mạnh hơn một lần chạy tình cờ.
- Client Java **GUI thật** 1.21.1 (`JavaReal12`) connect `127.0.0.1:25569`: gateway
  `CONFIGURATION → PLAY`, BDS `Player connected` + `Player Spawned`, log client **không** còn
  `disconnected with reason` / `InformativeException`.

**Build/pin & phát hành `.12`**: `ViaBedrock/gradle.properties` → `maven_version=0.0.31-mpmc.12`,
`ViaProxy/build.gradle` → `net.raphimc:ViaBedrock:0.0.31-mpmc.12`; `BedrockGateway.kt` pin
`PATCHED_VERSION = "3.4.14-mpmc.12"` và `PATCHED_SHA256 = "b8943ebb…"`. `./gradlew test
packageAgent` → `BUILD SUCCESSFUL`, **14 suite / 72 test / 0 fail / 0 error / 0 skip**. Gói
`build/dist/mpmc-agent-0.1.0.zip` được build lại: docs trong zip trùng hash local, **0** entry
`viaproxy|viabedrock|gateway-build`, **0** `ViaProxy-3.4.14.jar`, **0** class
`net/raphimc/viabedrock`; giải nén + copy jar `.12` vào `data/gateway/` →
`sh mpmc/bin/mpmc --data-dir data gateway verify` → `SHA-256 khớp (mpmc-patched
3.4.14-mpmc.12)`, `exit=0`.

**Ranh giới trung thực**: 77/169 packet clientbound Bedrock vẫn không có đối tác Java (được đăng
ký, tiêu thụ và audit, không dịch thành packet Java); 9 packet `java-counterpart-unmapped` vẫn
**[Chưa xác minh]**; LAN NetherNet/byulnuri, Java 1.21.7+, BDS 2225 và render đồ hoạ đầy đủ vẫn
**[Chưa xác minh]**. Không đưa số liệu hiệu năng chưa đo.

### 5.7 `.13` — serialize `ITEM_STACK_REQUEST` theo slot (sửa `status 49`)

**Phát hiện (đã xác minh bằng log).** Phiên GUI thật `JavaReal12` (22:17, jar `.12`) có 20 lần
`Bedrock rejected item stack request … (status 6 = InvalidRequestCraftActionType)`, tất cả
`oldNetId=null`. Đây là lỗi mở Task #19. Điều tra bằng CLI trên **cùng jar `.12`, cùng BDS
`127.0.0.1:19132`, cùng gamemode creative, cùng item/slot/action chain**:

| Biến số đã thử | Kết quả |
| --- | --- |
| 1 `SET_CREATIVE_MODE_SLOT` / click (`RaceA*`, `Probe13Main`, lab `.12` 25573) | BDS chấp nhận, cấp `oldNetId=185…194` |
| Cặp 2 packet / click (đúng hành vi GUI) | request 1 chấp nhận, request 2 **`status 49 = FailedToValidateSrcSlot`** (7/7, 8/8, 8/8) |
| 10 data component 1.21.1 hợp lệ, `held_item_slot`, `pick_item`, `window_click`, username `JavaReal12` | vẫn chỉ `status 49`, **`status 6` không tái hiện** |
| BDS không restart (12:26 → 00:47), `gamemode=creative`, `force-gamemode=false` | không đổi |

`status 6` gốc vẫn **[Chưa xác minh]** về nguyên nhân; nghi do trạng thái BDS tích lũy sau chuỗi
12 phiên race 22:08–22:15, tự sạch sau AutoCompaction 22:47. Không ghi nhận lại lần nào trong
các lab 00:17–00:47. Không đổi chuẩn action `CraftCreative(14) → CraftResults(19) → (Destroy) →
Take`, vì chuẩn này được BDS chấp nhận 53 lần ở `.13-exp`.

**Nguyên nhân `status 49` (đã xác minh).** GUI Java 1.21.1 phát **hai** `SET_CREATIVE_MODE_SLOT`
cho cùng slot trong cùng millisecond. Gateway cập nhật container lạc quan ngay sau request 1,
nhưng `netId` của stack chỉ có khi `ITEM_STACK_RESPONSE` về. Request 2 vì thế gửi action `Destroy`
với source `stackId = 0` / stale ⇒ BDS `FailedToValidateSrcSlot`.

**Sửa (root cause, không che triệu chứng).** Thêm hàng đợi per-user, keyed theo
`(ContainerEnumName, containerSlot)`:

- `InventoryTracker`: `deferredCreativeSlots` (`Map<Long, ArrayDeque<DeferredCreativeSlot>>`) và
  `inFlightCreativeSlots` (`Map<Long, Long>` = key → thời điểm bắt đầu chờ).
- `InventoryPackets.handleCreativeModeSlot`: nếu slot đang có request chờ, **enqueue** thay vì gửi;
  vẫn cập nhật lạc quan + echo `CONTAINER_SET_CONTENT` cho Java. Guard stale 5 s (BDS không trả
  lời) tự bỏ để không kẹt. Mọi early-return (item rỗng / không có creative entry / force sai) đều
  xoá guard.
- `ITEM_STACK_RESPONSE`: sau `applyItemStackResponseSlot` / `restoreCreativeSlot`, xoá guard rồi
  `flushDeferredCreativeSlot` — request kế tiếp lúc này có `oldItem.netId()` thật. Vòng lặp drain
  tiếp nếu request bị drop trước khi gửi.

**Kiểm chứng `.13` (lab `gateway-13` 25575 → BDS 19132, fat jar SHA `62670568…`).**

- `gui-repro.js` 8 kịch bản (cặp 2 packet, components, held/pick/window, 5 item gồm id âm
  `-960/-213/-596`): **15/15 accepted**, `rejected=0`, `status6=0`, `status49=0`. Trước fix trên
  `.13-exp`: 7/8 cặp có 1 `status 49`.
- Request thứ 2 nay có `oldNetId=271/273/276/280/283/285/287` — bằng chứng netId đã được BDS cấp
  trước khi gửi.
- Stress biên (`gui-edge-cases.json`, gap 80 ms, 24 packet: burst 5 cùng slot, đổi item liên tiếp,
  3 slot song song, item rỗng, item id 9999, burst 4 cuối): **21 request hợp lệ / 21 accepted**,
  3 drop đúng kỳ vọng (2 item rỗng + 1 không có creative entry), `rejected=0`, `timed out=0`,
  `oldNetId` tăng tuần tự `289 → 315`, không mất / không kẹt / không sai thứ tự.

**Build/pin/phát hành `.13`**: `maven_version=0.0.31-mpmc.13`,
`includeInJar "net.raphimc:ViaBedrock:0.0.31-mpmc.13"`, `BedrockGateway.kt` pin
`3.4.14-mpmc.13` / `626705681b98f0b01b600a3b5c64793ba392e4eaaadd6a30267a2ca4f31f221d`.
`publishToMavenLocal` 12 s + ViaProxy `build` 42 s, đều `BUILD SUCCESSFUL`; các dòng
`[ClassDowngrader] ERROR: Could not find class …` là noise đã biết.

**Ranh giới trung thực cập nhật**: `status 6` gốc chưa tái hiện được nên chưa thể khẳng định fix
này triệt tiêu nó; tuy nhiên cơ chế "hai craft-action chain chồng cùng tick" nay đã bị loại bỏ
hoàn toàn. Các ranh giới ở cuối mục 5.6 (77/169 clientbound không có đối tác Java,
9 `java-counterpart-unmapped`, LAN NetherNet/byulnuri, Java 1.21.7+, BDS 2225, render đồ hoạ) vẫn
giữ nguyên **[Chưa xác minh]**.

### 5.8 Deploy `.13` vào 2 gateway production + kiểm chứng BDS (lượt automation 00:45–01:10, 2026-11-12)

Tiếp nối §5.7 (mới chỉ chạy ở lab `gateway-13:25575`), lượt này đưa jar `.13` vào
**cả 2 thư mục gateway** và đo lại trên `gateway-test2 127.0.0.1:25570`.

- **Trạng thái đọc được lúc bắt đầu**: 2 gateway production 25569/25570 đã dừng; BDS
  test2 (UDP 19142) không còn chạy, chỉ còn BDS chính `1.26.51.1` PID 18420
  (UDP 19132). Giả định ghi rõ: cả 2 gateway trỏ `127.0.0.1:19132` (BDS chính),
  không dựng lại BDS test2. WSL, BDS, các gateway lab 25571/25573/25575/25576 và
  terminal được giữ nguyên.
- **Build lại đúng quy trình**: `./gradlew publishToMavenLocal -x test --offline --no-daemon`
  (ViaBedrock) → `./gradlew build -x test --offline --no-daemon` (ViaProxy), repo Maven
  trên ổ D `_m6/gateway-build/m2repo`. `BUILD_EXIT=0`, SHA fat jar **giữ nguyên**
  `626705681b98f0b01b600a3b5c64793ba392e4eaaadd6a30267a2ca4f31f221d` = pin — jar
  reproducible, đúng source hiện tại (source sửa 00:44:00, jar build 00:44:57;
  `javap` xác nhận class có `ReqConfig`/`flushDeferredCreativeSlot`/
  `creativeTakeSourceStackId`). **BUILD SUCCESSFUL không được dùng làm bằng chứng fix.**
- **Deploy**: copy jar vào `_m6/gw-data/gateway/` và `_m6/gw-data/gateway-test2/`
  (lưu bản cũ `.prev12`, không xoá), restart gateway main (dừng PID 21280 chạy `.12`
  rồi khởi động lại :25569) và khởi động gateway-test2 :25570. Cả 2 bind loopback,
  stderr **0 byte**.
- **Tái hiện baseline bằng CLI** (`_m5/client/gui-repro.js`, minecraft-protocol
  1.21.1, 8 kịch bản cặp creative-click) trên jar `.12` qua 25570:
  `vp-repro12-baseline.log` ghi **4× `status 49 = FailedToValidateSrcSlot`**, 0 accepted.
  `status 6` **không tái hiện được bằng CLI** — đúng ghi nhận 11/11 23:10 và §5.7.
- **Bằng chứng BDS chấp nhận trên `.13`**: cùng kịch bản (`FIX13A`) →
  `vp-mpmc13-test2.log` ghi **15/15 `Bedrock accepted` (status 0), 0 rejected**,
  7 `oldNetId` thật `389/391/394/398/401/403/405`. Bao gồm đúng họ item từng gây
  `status 6`: `-960 minecraft:damaged_anvil`, `-213 minecraft:composter`,
  `-596 minecraft:sniffer_egg`, `168 minecraft:prismarine`, `138 minecraft:beacon`.
  Dòng `Bedrock accepted` chỉ in khi `status == 0` đọc từ `ITEM_STACK_RESPONSE`
  (`InventoryPackets.java:592–599`) ⇒ phản hồi thật từ BDS, không phải log tự bịa.
  BDS `bds51-run.log`: `Player connected` + `Player Spawned`, **0 `PacketViolationWarning`**.
- **Hồi quy 5/5 `exit=0`**: `verify-actions.js` 1.21.1 (phiên đầu kẹt CONFIGURATION
  một lần, retry sạch), 1.21.4, 1.21.6 (spawn, stone×16, đặt/đào, đi/nhảy/sprint),
  `creative-slot-probe.js`, `use-item-on-probe.js` (`errors=[]`). Lần kẹt là race
  phiên đơn lẻ, không tái hiện khi retry; **[Chưa xác minh]** nguyên nhân chính xác
  của lần kẹt đó.
- **Test biên** (`edge-cases.json`, 15 case: item rỗng, count 0/255/256, request lặp
  5/10, `window_click` sai thứ tự, slot 200, empty→filled / filled→empty cùng slot):
  27 request sinh ra → **27 accepted / 0 rejected**, 6 case drop an toàn (1 WARN
  tường minh `Unhandled creative mode slot: 200`), `timed out=0`, stderr 0 byte.
  5 case drop không kèm WARN: **[Chưa xác minh]** tầng drop (encoder Java hay handler).
- **Tài liệu**: `docs/M6-PACKET-INVENTORY.md` kiểm kê 169 clientbound + 69 serverbound
  (bảng tự động `_m6/packet-inventory.py`, audit động `.13` 8249 dòng / 0 lỗi), cộng
  §5 bổ sung field-level: ID Java 26.3 lấy bằng `javap -c -p`, từng field/kiểu/
  endian/varint, cấu trúc `ITEM_STACK_REQUEST`/`ITEM_STACK_RESPONSE`, bảng
  `ItemStackNetResult` và bằng chứng runtime lượt này.
- **Ranh giới giấy phép giữ nguyên §0**: bản ViaBedrock/ViaProxy patched là tiến trình
  GPL riêng, chỉ nằm `_m6/gateway-build` + 2 thư mục gateway, **không** nhúng vào
  `mpmc-agent-*.zip`; proxy chỉ bind `127.0.0.1`; không sửa byte Minecraft sau HELLO,
  không spoof/tắt online-mode, không parse/inject sau Encryption Response, không log
  secret/token, chỉ iroh+iroh-relay, không TUN/TAP/admin.

**Ranh giới trung thực**: vẫn **chưa có cặp "trước `status 6` / sau status 0" trên
cùng một probe** (status 6 chỉ xuất hiện ở GUI thật 11/11, cần thao tác tay). Bằng
chứng hiện có là cùng họ item runtime id âm từng gây `status 6` nay được BDS chấp
nhận. `status 6` và `status 49` có chung root cause hay không, và cảm quan GUI thật
sau deploy `.13`: **[Chưa xác minh]**.


### 5.9 Lượt automation 02:21–02:4x — tái hiện độc lập, biên `count`, không đụng source (2026-11-12)

Tiếp nối §5.8. **Không sửa `InventoryPackets.java`, không build lại, không deploy
jar mới, không đổi pin `.13`, không đụng zip**: có phiên kỹ thuật song song đang
chạy trên `mpmc.14-exp` (`gradle.properties` 01:20, source sửa 02:04, jar
`5a794cb1…` build 02:05, lab `gateway-14*` tới 02:23). Sửa lúc này sẽ ghi đè công
việc đang chạy mà chưa có bằng chứng mới. Chỉ append có nhãn thời gian.

- **Môi trường**: BDS chính `1.26.51.1` PID 18420 (UDP 19132) vẫn sống; WSL,
  terminal, gateway lab `:25575` giữ nguyên. Functional connect probe lúc 02:24:
  `25569/25570/25571/25573/25576 ECONNREFUSED`, `25575 CONNECT_OK`. Start lại
  **gateway test2 `:25570`** bằng đúng jar production `.13`
  (`626705681b98…`), `--bind-address 127.0.0.1`, `--target 127.0.0.1:19132`,
  log mới `vp-aut12-test2.log`, stderr **0 byte**. (TCP probe trên `19132`
  ECONNREFUSED là bình thường: BDS dùng UDP.)
- **Tái hiện bằng CLI** (`_m5/client/*.js`, `minecraft-protocol` 1.21.1, 4 probe):
  `gui-repro.js` 8 case cặp creative-click → 15/15 status 0; `status6-burst.js`
  20 request cùng tick → 18 status 0 + **2 status 50 = FailedToValidateDstSlot**
  (req `-19`/`-39`, `sniffer_egg`), **0 `status 6`**; `status6-useitem-combo.js`
  (mới, creative + `USE_ITEM_ON` + pump 20 Hz) → **12/12 status 0**, log có đúng
  `USE_ITEM_ON tx fromItem=-960:0x1` như phiên GUI 11/11 23:10 nhưng vẫn
  **0 `status 6`**. Tổng log chốt lúc 02:3x: **47 accepted / 2 rejected /
  0 status 6 / 0 status 49 / 0 ERROR**, 37 dòng `oldNetId` thật, BDS
  0 `PacketViolationWarning`. Sau hồi quy 02:34–02:45 log là **50 accepted /
  2 rejected / 0 status 6 / 0 status 49 / 4 PacketMalformed**, stderr 0 byte;
  hồi quy **4/5 script `exit=0`** (1.21.4 kẹt CONFIGURATION state, dừng có chủ
  đích ⇒ **[Chưa xác minh]**), 2 dòng `SocketException: Connection reset` là hệ
  quả của lần dừng đó, không phải lỗi dịch packet.
  ⇒ Giả thuyết "chồng craft chain cùng tick" và "item runtime id âm + USE_ITEM_ON"
  đều **không tái hiện được status 6**; nguyên nhân gốc vẫn **[Chưa xác minh]**.
  Bằng chứng fix vẫn là `ITEM_STACK_RESPONSE status 0` của BDS, không phải
  `BUILD SUCCESSFUL`.
- **Test biên phát hiện lỗi mới**: `count=64` → status 0; **`count=65/100/127`
  → BDS kick `Packet violation warning: PacketMalformed (TerminatingConnection)`**;
  `count=128/255/256` → drop trước khi sinh `ITEM_STACK_REQUEST`, client sống.
  `edge-cases.json` lượt trước chỉ có 0/255/256 nên không lộ case này. Probe biên
  19 case lượt này bị đứt ở `count=127` ⇒ 17 case còn lại **[Chưa kiểm chứng]**,
  không được tuyên bố "19 case đều OK". Giả thuyết (`UNSIGNED_BYTE` count vượt
  `max_stack_size` 64 vẫn được gửi, BDS validate stack): **[Chưa xác minh]** —
  chưa capture byte encoder Java. Bẫy đọc log: `new=138:0x127` in **số thập phân
  sau `0x`** (=127), không phải hex 295.
- **Tài liệu**: `docs/M6-PACKET-INVENTORY.md` append §6 (phiên bản/state áp dụng
  cho mọi packet, sửa quy ước mức `Suy luận`, bảng **213 ID Java 26.3** lấy bằng
  `javap -c -p` từ jar `.13`, field-level từ log runtime, bảng trạng thái
  field-level trung thực với 158 clientbound + 62 serverbound ghi
  `[Chưa kiểm chứng]`). Chi tiết xem §6 của tài liệu đó.
- **§0 giữ nguyên**: không sửa byte sau HELLO, không spoof/tắt online-mode,
  không parse/inject sau Encryption Response, proxy chỉ bind loopback, không log
  secret/token, chỉ iroh+iroh-relay, không TUN/TAP/admin, bản patched là tiến
  trình GPL riêng không nhúng vào `mpmc-agent-*.zip`.

### 5.10 `.15` — clamp `count` creative, deploy đủ 2 gateway (lượt automation 03:49–04:0x, 2026-11-12)

Tiếp nối §5.9. Lượt này có **phiên song song đã chốt `mpmc.14`** lúc 03:08 (jar
`1d19a731…`, pin `.14`, zip `42d41767…`, 2 gateway production `:25569`/`:25575`).
Hai việc còn dở: `gateway-test2/` vẫn nạp `.13`, và `M6-PACKET-INVENTORY.md` bị
ghi đè mất §5/§6 field-level (trong zip còn 80 786 B, local chỉ 36 337 B).

- **Tái hiện bằng CLI** qua `127.0.0.1:25570` (jar `.14`, `minecraft-protocol`
  1.21.1): `gui-repro.js` 8 case + `status6-burst.js` 20 request ⇒ **33 accepted /
  0 rejected / 0 `status 6` / 0 status 49**. `status 6` gốc (GUI thật 11/11 23:10)
  **vẫn không tái hiện được bằng CLI**.
- **Phát hiện biên có căn cứ**: 14 case tách từng phiên ⇒ `count=64` status 0,
  **`count=65/100/127` → BDS kick `Packet violation warning: PacketMalformed
  (TerminatingConnection)`**, `128/255/256` và item rỗng drop an toàn, lặp 5/10 →
  5/10 accepted. Cùng layout `actions=3 crafts=1`, chỉ đổi `count` ⇒ nguyên nhân là
  giá trị amount vượt `max_stack_size`, không phải lệch byte cấu trúc.
- **Sửa root cause** trong `InventoryPackets.handleCreativeModeSlot`: clamp
  `count` tối đa 64 trước khi ghi `CraftResults` / `Take`. Bump
  `maven_version=0.0.31-mpmc.15`, dependency ViaProxy, pin `3.4.14-mpmc.15` /
  SHA `71ea40ade46832ece94379b8fb34a0f4fba42fb304223d26508c3645ea047481`.
  `publishToMavenLocal -x test --offline --no-daemon` (ViaBedrock) →
  `build -x test --offline --no-daemon` (ViaProxy), repo Maven trên D
  `_m6/gateway-build/m2repo`, `VB_EXIT=0` / `VP_EXIT=0`. `javap -c -p` xác nhận
  bytecode `bipush 64 → Math.min → Math.max` có thật trong jar.
  **BUILD SUCCESSFUL không được dùng làm bằng chứng fix.**
- **Deploy**: jar `.15` vào **cả 2** thư mục `_m6/gw-data/gateway/` và
  `_m6/gw-data/gateway-test2/` (backup `.prev14-aut0359` = `1d19a731…`, không xoá),
  restart `:25569` + `:25570`, cả 2 `--bind-address 127.0.0.1` → BDS
  `127.0.0.1:19132`, stderr **0 byte**. Lab `:25575` giữ nguyên process.
- **Bằng chứng BDS chấp nhận**: log `vp-mpmc15-test2.log` dòng 70–71 ghi
  `amount=65 count=64` → `Bedrock accepted item stack request -1` (status 0 đọc từ
  `ITEM_STACK_RESPONSE`); tương tự `amount=100` (79–80) và `amount=127`. **Tổng
  57 accepted / 0 rejected / 0 `status 6` / 0 status 49 / 0 `PacketMalformed` /
  29 `oldNetId` thật**, 19 connect / 19 disconnect, stderr 0 byte; BDS
  `bds51-run.log` **0 `PacketViolationWarning`**. 2 dòng `SocketException:
  Connection reset` là hệ quả đóng sớm 2 phiên CLI timeout, không phải lỗi dịch.
- **Hồi quy 5/5 `exit=0`**: `verify-actions.js` 1.21.1 / 1.21.4 / 1.21.6,
  `creative-slot-probe.js`, `use-item-on-probe.js` (`errors=[]`,
  `stillConnected=true`). Lần kẹt CONFIGURATION 1.21.4 của lượt 02:2x không lặp
  lại, nhưng mới chỉ một lần quan sát ⇒ **[Chưa xác minh]**.
- **Tài liệu**: `docs/M6-PACKET-INVENTORY.md` giữ bảng packet-level `.14` (§1–§4),
  append §5 field-level (ID/hướng/phiên bản/từng field/kiểu/endian/varint/Java
  counterpart/4 mức xác minh cho 11 clientbound + 7 serverbound inventory, cấu trúc
  con `ITEM_STACK_REQUEST`/`RESPONSE`, 213 ID Java 26.3, bảng trạng thái trung thực
  158 + 62 + 9 packet chưa kiểm kê field-level) và §6 kết quả lượt này.
- **§0 giữ nguyên**: không sửa byte sau HELLO, không spoof/tắt online-mode, không
  parse/inject sau Encryption Response, proxy chỉ bind loopback, không log
  secret/token, chỉ iroh+iroh-relay, không TUN/TAP/admin, bản ViaBedrock/ViaProxy
  patched là tiến trình GPL riêng **KHÔNG** nhúng vào `mpmc-agent-*.zip`.

**Ranh giới trung thực**: `status 6` vẫn chưa có cặp "trước status 6 / sau status 0"
trên cùng probe (chỉ có ở GUI thật); `.15` triệt tiêu được lỗi biên `count` đã đo
trực tiếp, nhưng không khẳng định triệt tiêu `status 6` gốc. Clamp cứng 64 chưa
đo với item có `max_stack_size < 64`: **[Chưa xác minh]**. Chi tiết số liệu, bảng
biên và danh sách việc còn lại xem `docs/M6-PACKET-INVENTORY.md` §6.

### 5.11 `.16` — clamp `[1,64]` tập trung, kiểm kê field-level, săn `status 6` (lượt automation 05:11–05:4x, 2026-11-12)

**Đọc trạng thái**: lúc bắt đầu cả 2 gateway production `:25569`/`:25570` đều
`ECONNREFUSED`, chỉ còn lab `:25575` (jar `.14` trong bộ nhớ) và BDS PID 18420. Không
có phiên song song đang sửa source (pin/docs/zip mtime 04:08–04:09 khớp lượt 03:49).
Start lại cả 2 với jar `.15` bind `127.0.0.1:19132`, stderr 0 byte.

**Giả thuyết + tái hiện bằng CLI**. Log gốc `vp-mpmc12-main.log` (22:17–22:45) cho 20
dòng `status 6 = InvalidRequestCraftActionType`, tất cả `oldNetId=null`, thường thành
cặp 2 request cùng slot, với 5 `creativeId` xác định. Dump `CREATIVE_CONTENT` thật
(1980 entry, `-Dmpmc.creative.dump`) xác nhận ánh xạ: `450→prismarine(168)`,
`885→sniffer_egg(-596)`, `1455→damaged_anvil(-960)`, `1475→composter(-213)`,
`1602→beacon(138)`. **Đính chính** memory 11/11 23:10 vốn ghi ngược `138/168`.
Ép đúng 5 entry bằng `cfg.force` + cặp 2 click (`gui-repro.js`) → **status 0**.
Sweep 10 tổ hợp action chain qua `-Dmpmc.req.control`: `results-only` →
`status 3 = ActionRequestNotAllowed`, `create` → `status 11 = InvalidCraftResultItem`,
8 mode còn lại → status 0. **0 `status 6`**. ⇒ Giả thuyết "sai action chain sinh
status 6" bị loại bỏ có bằng chứng; nguyên nhân gốc vẫn **[Chưa xác minh]** (nghi do
trạng thái BDS tích lũy, tự sạch sau `AutoCompaction` 22:47). Cặp "trước status 6 /
sau status 0" trên **cùng một probe** vẫn chưa có (trước là GUI thật `.12`, sau là CLI
`.16`). Không khẳng định `.16` triệt tiêu `status 6` gốc.

**Phát hiện byte-level**: `mode=empty-descriptor` trên `.15` làm BDS trả
`PacketMalformed (TerminatingConnection)` kèm
`Violating Packet: ItemStackRequest` / `the input is outside of the accepted range
[1, 64]` / `readNoHeader failed! packetId: 147`. ⇒ BDS 1.26.51 validate **mọi** field
số lượng / `timesCrafted` của `ITEM_STACK_REQUEST` (147) trong `[1,64]`; `count=0`
của descriptor rỗng và `count=65/100/127` là cùng một lỗi. Giải đáp
"[Chưa xác minh] cần capture byte encoder Java" của các lượt trước.

**Sửa root cause (`.16`)**: thêm `clampStackCount(int) = max(1, min(v, 64))` trong
`InventoryPackets.java`, dùng cho 5 vị trí ghi số lượng (`count` creative,
`timesCrafted` ×2, `Destroy count`, `StackRequestItem count`). Không đổi wire
production, không đổi thứ tự action. Bump `0.0.31-mpmc.16` + dependency ViaProxy +
pin `3.4.14-mpmc.16` / SHA `44bfbe82…`. Build đúng quy trình
`publishToMavenLocal -x test --offline --no-daemon` → `build -x test --offline
--no-daemon` (repo Maven trên D `_m6/gateway-build/m2repo`): `VB_EXIT=0`, `VP_EXIT=0`;
`javap -c -p` đếm 6 tham chiếu `clampStackCount`. Deploy vào **cả 2** thư mục gateway
(backup `.prev15` = `71ea40ad…`, không xoá), restart `:25569` + `:25570`, bind loopback,
stderr 0 byte. Lab `:25575` không kill.

**Bằng chứng BDS chấp nhận (KHÔNG phải BUILD SUCCESSFUL)**: cùng probe
`mode=empty-descriptor,force=1455`, cùng BDS — `.15` 05:19:43 kick `PacketMalformed`,
0 accepted; `.16` 05:31:54 `Bedrock accepted item stack request -1` (**status 0**),
0 malformed, client sống. Log `vp-mpmc16-test2.log`: **295 dòng / 67 accepted (status 0)
/ 0 rejected / 0 `status 6` / 0 `PacketMalformed` / 35 `oldNetId` thật / 10 CONNECT /
10 DISCONNECT / stderr 0 byte**; BDS `bds51-run.log` **0 `PacketViolationWarning`**.

**Biên + hồi quy**: 19 case biên (item rỗng, count 0/1/127/255/256, lặp 5/10,
`window_click` sai thứ tự, slot 200, empty↔filled) `exit=0`, không kick/không timeout;
burst 20 request `exit=0`; baseline 8 case `exit=0`. Hồi quy **5/5 `exit=0`**
(`verify-actions.js` 1.21.1 / 1.21.4 / 1.21.6, `creative-slot-probe.js` — 1 retry do
kẹt CONFIGURATION 35 s, `use-item-on-probe.js` `errors=[]`). Lần kẹt CONFIGURATION
**[Chưa xác minh]** nguyên nhân.

**Tài liệu**: `docs/M6-PACKET-INVENTORY.md` §7 (field-level `ITEM_STACK_REQUEST` 147 /
`CREATIVE_CONTENT` theo từng trường, kiểu, endian/varint, Java counterpart, 4 mức xác
minh; bảng action type lấy từ enum nguồn; bảng sweep; cập nhật trạng thái kiểm kê
trung thực 158 + 62 + 9 packet **[Chưa kiểm chứng]**).

**Ranh giới trung thực**: `status 6` chưa tái hiện được bằng CLI dù đã thử đúng 5
creative entry, cặp 2 click và 10 tổ hợp action; clamp cứng 64 chưa đo với item
`max_stack_size < 64`; 158 clientbound + 62 serverbound chưa kiểm kê field-level;
raw Bedrock frame id, bảng identifier Bedrock↔Java đầy đủ, LAN NetherNet/byulnuri,
Java 1.21.7+/1.21.11, BDS 1.26.60/2225, render đồ hoạ/death screen: **[Chưa xác minh]**.
§0 giữ nguyên (không sửa byte sau HELLO, không spoof/tắt online-mode, không parse/inject
sau Encryption Response, proxy chỉ bind `127.0.0.1`, không log secret/token, chỉ
iroh+iroh-relay, không TUN/TAP/admin, bản patched là tiến trình GPL riêng KHÔNG nhúng
vào `mpmc-agent-*.zip`). BDS/WSL/gateway/terminal giữ nguyên (chỉ restart có chủ đích
2 gateway production để nạp jar mới).

### 5.12 `.17`–`.18` — max stack size, `USE_ITEM_ON` sequence, `INTERACT`, `getEntityByJid` (2026-11-12)

**`.17`** (SHA `0a6f3c80…`): `clampStackCount(int, BedrockItem)` đọc bảng
`BEDROCK_STACK_LIMITS_DATA` (288 entry sinh từ `items.json` Bedrock 1.26.51). BDS validate
`amount` theo `max_stack_size` **riêng của từng item** (egg/snowball/ender_pearl = 16), nên
`clampStackCount(int)` chung `[1,64]` vẫn sinh `status 51 InvalidAdjustedAmount`. Đã đo trên
BDS thật trước khi chốt.

**`.18`** (SHA `bdeab29f84e9bfa81714da15da49e611438d04e71aa93d088ec0854bdf4f78f8`) sửa 3 lỗi
root-cause, build `BUILD SUCCESSFUL` (`build-mpmc18-vb5.log` / `build-mpmc18-vp5.log`):

1. **Java 1.21.1 `USE_ITEM_ON` luôn `seq=0`** → client nhận `BlockChangedAck(0)`, gây ghost
   block / giật khi đặt block. Nguyên nhân: ViaBackwards
   `BlockItemPacketRewriter1_21_2` passthrough `hand/position/face/cursor/insideBlock` rồi ghi
   `worldBorderHit=false`, **không** passthrough `sequence` cuối; byte VarInt `sequence` nằm lại
   trong `inputBuffer`, trong khi `PacketWrapperImpl.read()` ưu tiên `readableObjects`. Fix:
   `InteractionPackets.java` đọc sequence trực tiếp từ
   `((PacketWrapperImpl) wrapper).getInputBuffer()` khi không còn `Types.VAR_INT` typed nào.
   Đo lại: `seq=1..80`, không còn `seq=0`.
2. **Right-click entity (`use_entity`) không tới được `BedrockProtocol`**. Diagnostic
   `-Dmpmc.debug.pipeline=true` (mặc định TẮT) chèn `PipelineProbe` no-op vào `protocolList`
   serverbound và chứng minh gói id `22 → 24 → 25` dừng sau
   `step=9:before:Protocol26_1To1_21_11` (691 → 690 probe). Bytecode
   `EntityPacketRewriter26_1.lambda$registerPackets$3`: `action=0` gọi `wrapper.cancel()`.
   Fix: `BedrockProtocol.installInteractCompatibility()` dùng ViaVersion public API
   `replaceServerbound(ServerboundPackets1_21_6.INTERACT, handler)` (giữ nguyên ID mapping,
   chỉ thay handler) và sinh `LOW_PRECISION_VECTOR(0,0,0)` cho `action=0`. Lần đầu dùng
   `registerServerbound` gây `IllegalArgumentException: Packet type INTERACT already registered!`
   — đã sửa. Đo lại: `INTERACT PRE/POST/CANCEL = 15/11/4`, `INTERACT toHost = 11`,
   `outName=INVENTORY_TRANSACTION`.
3. **`EntityTracker.getEntityByJid()` NPE** khi Java click entity id không có trong Bedrock
   tracker (`Int2ObjectMap.get()` trả `null` rồi unbox). Fix: trả `null` (mọi caller đã có
   `if (entity == null) cancel()`). Đo lại: `NullPointerException 0`, `PROXY KICK 0`.

**Hồi quy sạch trên BDS 1.26.51 (protocol 2193), log `vp-audit18-reg.log`:**
`audit_lines=44164`; `PHASES {'PRE': 21988, 'CANCEL': 2211, 'POST': 19737, 'DEFERRED': 40,
'CLASSIFIED': 188}`; `PRE = POST + CANCEL + DEFERRED` đúng; `POST toHost=true: 4189`;
`POST unmapped=true: 0`; `AUDIT ERROR: 0`; `UNKNOWN_BEDROCK_ID: 0`; `hasHandler=false: 0`;
`UNCLASSIFIED: 0`; `SEVERE: 0`; `PROXY KICK: 0`; `Caught unhandled: 0`;
`creative accepted: 50`; `creative rejected: 0`. 2 gateway production `25569`/`25570` đã
restart với jar `.18`; hash jar trùng pin trong `BedrockGateway.kt`.

**GUI thật trên `.18` (19:53–…, user `JavaReal18`, qua raw-frame proxy `25574 → 25570`)**:
`Connected successfully!` → `Switching to PLAY state` lúc 19:53:42–43; đến lúc chốt tài liệu
đã có **66 `SET_CREATIVE_MODE_SLOT`**, 20 `USE_ITEM_ON`, 6 `CONTAINER_OPEN`, 12
`CONTAINER_CLOSE` (đếm theo dòng `[AUDIT]`), **26 `Bedrock accepted item stack request`**,
**0 `Bedrock rejected`**, 0 `SEVERE`/kick/`NPE`/`PacketMalformed`, 0 `UPDATE_TRADE`.
`raw18-gui.log` capture 12.026 frame (có nén) để đối chiếu packet ID. ⇒ `status 6/60`
**chưa tái hiện** trên `.18` GUI; chưa thể gọi là "đã fix" vì trigger gốc (tương tác
entity/trade của phiên `.17`) chưa xảy ra trong phiên này — vẫn `[Chưa xác minh]`.

**Phát hành**: `./gradlew packageAgent` build lại zip (`BUILD SUCCESSFUL in 22s`);
`gateway verify` trên zip giải nén + jar `.18` → `VERIFY_EXIT=0`,
`SHA-256 khớp (mpmc-patched 3.4.14-mpmc.18)`; `unzip -l` xác nhận **không có** entry
`ViaProxy-*.jar` trong `mpmc-agent-0.1.0.zip`.

**Còn `[Chưa xác minh]` — lỗi GUI-only `status 6` / `status 60`:** phiên GUI thật `.17`
(`vp-live-25569.clean.log`, user `JavaReal17`) accept 13 request tới `16:52:01`, rồi từ
`16:52:55` **mọi** `ITEM_STACK_REQUEST` bị BDS từ chối: `status 6 =
InvalidRequestCraftActionType` (chain `CraftCreative/CraftResults/(Destroy)/Take`) và
`status 60 = CannotDestroyItem` (click xoá item, chain chỉ `Destroy`), kéo dài tới disconnect
`17:08:46`. Cùng `creativeId=931` (villager_spawn_egg) và cùng layout `actions=3/4 crafts=1`
lại **accept** ở CLI `.18` ⇒ khác biệt là trạng thái phiên, không phải item hay action chain.
Các thí nghiệm tái hiện bằng CLI đều **không** sinh status 6/60: burst 20 request cùng tick,
creative + `USE_ITEM_ON` + item runtime id âm, `close`/`gui-like`/`idle`/`flood`/`entity-all`,
spawn egg + click 16 entity id (`status6-trade-repro.js`: 6/6 accept, 0 `UPDATE_TRADE`), và
phiên đơn **150 s** + movement 20 Hz + 49 creative click kể cả delete
(`status6-longrun.js`: **47/47 `Bedrock accepted`, 0 rejected, 0 `SEVERE`/kick/`NPE`**). Giả thuyết đang mở: BDS mở màn hình **trade** qua
`UPDATE_TRADE` (id 80, gateway tiêu thụ dưới nhãn `java-counterpart-unmapped`) sau khi GUI
right-click villager/trader, khiến `CraftCreative` bị từ chối — **chưa có bằng chứng wire**,
không được tự ý sửa. Cần capture raw frame của chính phiên GUI thật (proxy `raw-frame-proxy.js`
đặt trước gateway audit) để có cặp "trước status 6 / sau status 0" trên cùng phiên.
Không đánh dấu PASS cho phần này.

## 6. Ranh giới giấy phép

- ViaProxy và ViaBedrock là **GPL-3.0**, chạy như tiến trình độc lập; mpmc **không nhúng**
  jar/binary/mã nguồn vào `mpmc-agent-*.zip`. `gateway install` tải bản phát hành chính thức (pin
  SHA-256) hoặc cài một jar có sẵn do người dùng cung cấp; cả hai đều được kiểm hash trước khi chạy.
- Bản **mpmc-patched** (ViaProxy 3.4.14 + ViaBedrock 0.0.31-mpmc.16) được build từ mã nguồn GPL của
  upstream, giữ **ngoài** gói phát hành (`_m6/gateway-build`, không đóng gói). Vì là tác phẩm phái
  sinh của GPL-3.0, nếu phân phối jar này phải kèm mã nguồn tương ứng và giấy phép GPL-3.0; mpmc chỉ
  cài từ tệp người dùng trỏ tới hoặc hash đã pin.
- Cách dựng lại bản patched: clone upstream ViaBedrock/ViaProxy đúng tag (ViaBedrock 0.0.31 tương ứng
  ViaProxy 3.4.14), áp các thay đổi ở mục 5.2, **5.3, 5.4 và 5.5**, bump `maven_version` trong
  `ViaBedrock/gradle.properties` + dependency trong `ViaProxy/build.gradle`, rồi chạy
  `./gradlew publishToMavenLocal -x test --offline` cho ViaBedrock và
  `./gradlew build -x test --offline` cho ViaProxy. Cả hai `gradle.properties` cần
  `systemProp.maven.repo.local` trỏ repo trên ổ D (`_m6/gateway-build/m2repo`). Các dòng
  `[ClassDowngrader] ERROR: Could not find class …` khi build ViaProxy là noise đã biết, build vẫn
  `BUILD SUCCESSFUL`. `gateway status` / `gateway verify` dùng được với jar đó khi hash trùng
  `PATCHED_SHA256` hoặc khi cài bằng `--from --sha256`.
- Sau khi đổi pin/hash, build lại gói phát hành bằng `./gradlew packageAgent -x test --offline` để
  `agent-core`/`agent-cli` trong `mpmc-agent-*.zip` mang đúng pin. `packageAgent` chỉ đóng gói
  `installDist` + `packaging/` + `docs/`; **không** có jar ViaProxy/ViaBedrock trong zip.
- Không decompile và không sao chép mã độc quyền. xver (MIT) và byulnuri-headless chỉ dùng để
  **nghiên cứu hành vi/phiên bản**; không có mã nào được sao chép. BDS là phần mềm độc quyền của
  Mojang, chỉ dùng làm đích test cục bộ.
- Không tái phát hành jar ViaProxy trong bất kỳ artefact nào của mpmc.

## 7. Giới hạn / chưa xác minh

- Chỉ một máy: chưa test hai máy khác mạng/NAT với relay TLS thật cho luồng Bedrock.
- Chưa test client Java chính thức có đăng nhập Microsoft; bot dùng offline auth.
- Chỉ đích `Bedrock 1.26.51`; chưa hỗ trợ 1.26.60/2225 hay bridge nhiều phiên bản Bedrock.
- Chiều ngược lại (client Bedrock vào server Java, không tài khoản Xbox) **đã dựng và đo thật**
  bằng `clientclone` → Geyser + ViaProxy → Java 1.21.11 (mục 10). Vẫn **chưa** hỗ trợ client Bedrock
  đồ hoạ gốc / NetherNet, và phần này là thiết lập lab ngoài agent, không nằm trong
  `mpmc-agent-*.zip`.
- Lỗi `RESPAWN` khi `ChangeDimension` và lỗi kẹt `y ≈ 32768` **đã sửa** ở bản `mpmc.3` (mục 5.1,
  5.3); trên BDS RakNet mọi phiên có kiểm tra toạ độ đều spawn đúng `y = -60` ngay lần đầu (kể cả
  client 1.21.6). Cờ `--ignore-protocol-translation-errors` vẫn bật như lưới an toàn chung nhưng
  không còn che lỗi này. **Chưa test lại world LAN NetherNet / byulnuri sau khi vá** —
  **[Chưa xác minh]**. Đã dò lại bằng listener thụ động trên UDP 7551 trong 20 s (`SO_REUSEADDR`,
  không gửi gói, không đụng Overlix PID 872): chỉ thấy **135 `RequestPacket`** từ 2 địa chỉ
  (`172.31.80.1:8554`, `192.168.1.219:8554`), **0 `ResponsePacket`** ⇒ không có world LAN nào đang
  mở, nên chưa thể test. Đổi chiều Overworld ↔ Nether qua portal **đã kiểm chứng** trên BDS test
  riêng (mục 5.4); riêng đích LAN NetherNet / byulnuri vẫn **[Chưa xác minh]**.
- 77/169 packet clientbound Bedrock không có đối tác Java: 21 `cancelClientbound` tường minh
  (mục 5.3) + 56 phân loại trong `CLIENTBOUND_DISPOSITIONS` (mục 5.5). Chúng **không** được dịch
  thành gói Java mà được đăng ký, tiêu thụ và kết thúc sạch có audit. Trên `.6` đã chốt
  **169/169 clientbound + 69/69 Java serverbound được quyết định tường minh, 0 fallback**; số đo
  runtime 6 phiên: **PRE 11.974 = POST 6.923 + CANCEL 5.006 + DEFERRED 45**, 0 `ERROR`,
  0 `unmapped=true`, 0 `hasHandler=false`, 0 dòng lỗi ngoài audit, stderr 0 byte. Phủ sóng ngữ
  nghĩa 100% cho mọi packet type / mọi phiên bản Bedrock khác: **[Chưa xác minh]**.
- Nhánh `TRACKED_WAYPOINT` trên client Java ≥ 1.21.6 **đã kiểm chứng** (mục 5.3): 2 bot nhận 3 gói
  mỗi bot trên `.3`; trên `.4` (`waypoint-mpmc4.log`) mỗi bot nhận 1 gói `track` (không sinh
  `update` trong kịch bản đó, không phải mất gói). Chưa thử client Java 1.21.7+ / 1.21.11 với gateway này — **[Chưa xác minh]**.
- Fork ViaBedrock/ViaProxy **không có unit test** (`ViaProxy/src` có 0 file test; ViaBedrock chỉ có
  `src/tool/.../ResourcePackConverterTest.java` trong source set `tool`, không thuộc task `test`).
  Độ phủ của các patch dịch packet vì thế là integration runtime (9 phiên `mpmc.3` + 4 phiên
  `mpmc.4` + 6 phiên audit `mpmc.6`), không phải unit test.
- Chưa xác minh render đầy đủ của client Java (không có client đồ hoạ chính thức trong test); bằng
  chứng hiện có là chunk/block tải được (`blockAt` trả block thật), spawn, toạ độ, health/food, chat,
  và các hành động đã được **host Bedrock xác nhận** qua giải mã `PlayerAuthInput`/correction (đi,
  sprint, nhảy, đào block, vị trí lưu qua lần join sau). Bot test dùng JSON log, không dựng cửa sổ
  đồ hoạ.
- ViaProxy mở thêm HTTP server resource pack trên `127.0.0.1:15119`; chỉ loopback.
- Log ViaProxy nằm cục bộ và có thể chứa IP/username người chơi (mặc định `log-ips=true` của
  ViaProxy); không chia sẻ tệp này. mpmc không ghi secret vào log của mình.

## 8. Không làm trong M6

- **Metadata `edition`/`protocol` cho phòng (M6.1)**: hoãn. Tunnel không đổi hành vi theo edition; thêm
  cột DB + DTO chỉ để hiển thị, trong khi join vẫn nhận đúng đích qua ticket. Sẽ làm khi có UI/room
  list cần phân biệt.
- Không tích hợp xver hay thư viện protocol Bedrock nào vào agent; mọi dịch giao thức do tiến trình
  ngoài đảm nhiệm.

## 9. Tuân thủ §0

| Ràng buộc | Cách đáp ứng |
|---|---|
| Không sửa byte Minecraft | Chỉ chuyển byte thô; ViaProxy là tiến trình riêng ngoài tunnel |
| Không tắt/spoof `online-mode` | Không can thiệp; BDS test để `online-mode=false` chỉ vì môi trường lab |
| Proxy bind loopback | `BedrockGateway.validateBind` từ chối mọi host không loopback |
| Join không chọn đích | Đích chỉ đến từ `--bedrock` của host |
| Không log secret | mpmc không log token/ticket; gateway log là của ViaProxy |
| Không tự viết transport | Vẫn iroh + iroh-relay; RakNet/NetherNet do ViaProxy/BDS xử lý |
| Không copy GPL/AGPL | Không nhúng/link mã GPL vào mpmc; gateway là tiến trình GPL riêng, build ngoài zip, cài từ hash đã pin |
| Không số liệu bịa | Mọi số ở mục 5 lấy từ log test thật; phần chưa đo ghi rõ "chưa xác minh" |

## 10. Chiều ngược: client Bedrock không Xbox → server Java (2026-11-12)

Thiết lập **lab tách biệt**, không thuộc `mpmc-agent-*.zip`. Mục tiêu: chứng minh client Bedrock
terminal tự ký (không tài khoản Xbox) vào được server Java offline, dịch gói tin hai chiều sạch.

### 10.1 Chuỗi đã chạy thật

```text
clientclone (Go, terminal, self-signed, RakNet, proto 2225)
  -> Geyser 2.12.1-b1302   UDP RakNet 127.0.0.1:19132 (transport=both),
                           NetherNet UDP 19134, auth-type=offline,
                           advanced.bedrock.validate-bedrock-login=false
  -> ViaProxy 3.4.14       TCP 127.0.0.1:25566, [26.3 <-> 1.21.11]
  -> Java 1.21.11 server   TCP 127.0.0.1:25565, online-mode=false
```

### 10.2 Các chướng ngại đã đo và cách xử lý

| Triệu chứng đo được | Nguyên nhân | Xử lý |
|---|---|---|
| `transport: raknet` → `WARNING! Raknet-only transport has now been REMOVED`, Geyser ép sang NetherNet; loopback `127.0.0.1` làm STUN timeout, `Send failed errno=10051` | Geyser 2.12.1 bỏ RakNet-only | `transport: both`: comment trong `config.yml` xác nhận **RakNet vẫn nghe UDP `port` (19132)**, NetherNet dùng `webrtc-port` |
| `transport: both` chết lúc init: `AssertionError: Unable to fetch discovery data … SocketTimeoutException` | mạng nhất thời tới `client.discovery.minecraft-services.net`; đo lại `HTTP=200 0.9s` | khởi động lại; không phải giới hạn cố định |
| `Please log into Xbox to join this server.` | `auth-type: offline` chỉ bỏ auth **phía Java**; Geyser vẫn verify chuỗi JWT Bedrock | `advanced.bedrock.validate-bedrock-login: false`. Geyser in `XBOX AUTHENTICATION IS DISABLED` — rủi ro spoof username, **chỉ chấp nhận vì bind loopback** |
| `This server does not support Java Edition 26.3 … Incompatible client! Please use 1.21.11` | Geyser 2.12.1 nói Java **26.3**, server lab là **1.21.11** | ViaProxy làm cầu `[26.3 <-> 1.21.11]`. Không tải server 26.3 vì manifest Mojang ghi `javaVersion.majorVersion=25` mà máy chỉ có JDK 21 |
| `UDP [::1]:19133` đã bị `Overlix.exe` (PID 16792) giữ | xung đột cổng NetherNet | `webrtc-port: 19134`; tắt `stun-discovery`, `advertise-addresses: ["127.0.0.1"]` |
| `2088 GameData entries vs 2080 pinned 2225 entries`, `1 ITEM_UNKNOWN_ID` | Geyser chèn **8 custom item** | `clientclone -live-item-registry` (xem 10.4) |

### 10.3 Bằng chứng 3 tầng (log thật)

- `clientclone`: `EXIT=0`, `ok=true`, **12/12 assertion OK**, `spawned=true`, 118 `LevelChunk`,
  120 `PlayerAuthInput`, 2287 item stack decode, `decode_errors=0`, `send_errors=0`,
  `unknown_packets=0`, `items_unknown=0`, `registry_match=true`, `world_name=Geyser`.
  Lệnh mở rộng gồm `chat`, `look`, `move`, `jump`, `sneak`, `sprint`, `hotbar`: 21/21 lệnh OK.
- Java server `_m5/server/logs/latest.log`: `ClientClone logged in with entity id 6`,
  `ClientClone joined the game`, `[Not Secure] <ClientClone> hello from offline bedrock client`,
  rồi `left the game` sạch.
- ViaProxy `viaproxy-bridge/viaproxy.log`: `[CONNECT] [26.3 <-> 1.21.11]`,
  `Filling player data` → `CONFIGURATION state` → `PLAY state` → `[DISCONNECT] Connection closed`,
  **0** exception.
- Geyser `geyser-viaproxy.log`: `Player connected with username ClientClone (2225)`,
  `has connected to remote java server`. Disconnect sau `quit` là
  `Bedrock client timed out` (Geyser giữ session vài giây) — không phải lỗi dịch.

### 10.4 Ranh giới phiên bản Bedrock (đo bằng `java-proto-probe.jsonl`)

| `-proto` | Bedrock | Kết quả |
|---|---|---|
| 2168 | 26.44 | `ok=true` (tên `Probe2168b`; lần đầu trùng `already logged in` do session trước chưa dọn) |
| 2169 | 26.45 | `ok=true` |
| 2193 | 26.50 | `ok=true` |
| 2225 | 26.60 | `ok=true` |
| 1001 | 26.30 | Bị từ chối: `Outdated Bedrock client! … 26.40, 26.41, 26.42, 26.43, 26.44, 26.45, 26.50, 26.51, 26.52, 26.60, 26.61, 26.62, 26.63` |

Geyser khai báo registry **lớn hơn** bảng chuẩn: 2225 → 2088/2080, 2193 → 2084/2076,
2168/2169 → 1984. Vì vậy `clientclone` có cờ `-live-item-registry` (**mặc định tắt**, hành vi
BDS/relay cũ không đổi): resolve ID theo `GameData.Items` của server, và `item_registry` dùng
`pinnedItemsPresent` — yêu cầu mọi item chuẩn hiện diện với đúng runtime ID, cho phép item
custom của server, vẫn từ chối item thiếu / đổi ID / tên trùng ID khác. Có unit test
`TestPinnedItemsPresent`. `go test -buildvcs=false ./cmd/clientclone/...` PASS.

### 10.5 Còn `[Chưa xác minh]` / rủi ro

- §10.1 chạy loopback một máy; riêng **NetherNet/WebRTC đã test thật** ở §10.7 qua
  `xvrelay -listen-nethernet` + LAN discovery trên IP LAN `192.168.1.219` (vẫn cùng một máy,
  chưa có thiết bị thứ hai). **Client Bedrock đồ hoạ gốc / thiết bị LAN khác: [Chưa xác minh]**.
- Chưa test **client Bedrock đồ hoạ gốc**; `clientclone` là terminal client tự ký, `joiner_new.exe`
  cũng là client terminal (không có codec 2225 cho đường NetherNet).
- Chưa test đặt block / inventory / tương tác entity ở chiều ngược; smoke mới cover join, chat,
  look, move, jump, sneak, sprint, hotbar.
- Chưa có codec cho 26.61/26.62/26.63 trong `clientclone` ⇒ chưa đo 3 bản Geyser hỗ trợ đó.
- `validate-bedrock-login=false` **không dùng cho production/public**: cho phép spoof username.
  Chỉ an toàn vì `bedrock.address: 127.0.0.1`.
- Geyser + ViaProxy là **tiến trình ngoài**, không nhúng vào agent, không có trong
  `mpmc-agent-*.zip`; giữ nguyên ranh giới GPL ở §6/§9.

### 10.6 File / lệnh tái dựng

```text
_m6/geyser/Geyser-Standalone.jar   SHA ccafefeb…  2.12.1-b1302
_m6/geyser/config.yml              transport=both, webrtc-port=19134, auth-type=offline,
                                   validate-bedrock-login=false, java.port=25566
_m6/geyser/viaproxy-bridge/        working dir riêng của ViaProxy, log viaproxy.log
_m6/geyser/geyser-viaproxy.log     log Geyser
_m5/server/logs/latest.log         log Java 1.21.11
bedrock-cross-version-relay/cmd/clientclone/scripts/
    java-join.jsonl                smoke chính (2225)
    java-join-2193.jsonl           smoke 2193
    java-proto-probe.jsonl         probe đa protocol (join không hard-code proto)
bedrock-cross-version-relay/dist/clientclone-java-join.json   report ok=true
bedrock-cross-version-relay/dist/clientclone-java-2193.json   report ok=true
```

Thứ tự khởi động: Java server → ViaProxy bridge → Geyser → `clientclone`. Lệnh:

```bash
# tại _m6/geyser/viaproxy-bridge, jar lấy từ _m6/gw-data/gateway/ViaProxy-3.4.14.jar
java -jar ViaProxy-3.4.14.jar cli --bind-address 127.0.0.1:25566 \
  --target-address 127.0.0.1:25565 --proxy-online-mode false --chat-signing false
# tại _m6/geyser
java -jar Geyser-Standalone.jar
# tại bedrock-cross-version-relay
./dist/clientclone.exe -script cmd/clientclone/scripts/java-join.jsonl \
  -addr 127.0.0.1:19132 -direct -proto 2225 -live-item-registry \
  -duration 30s -v -json dist/clientclone-java-join.json
```

Ghi chú: probe đa protocol cần `-name` khác nhau mỗi lần (Geyser giữ username vài giây sau
disconnect), và `-live-item-registry`.

### 10.7 LAN world card broadcast ra `.255` cho client không Xbox (2026-11-12, 23:36)

Yêu cầu: *"Bạn phải bật boardcast ra 255 nữa để tôi join vì client ko login xbox thì cái
join server ở menu ko phát sáng"*. Client không đăng nhập Xbox chỉ thấy world LAN qua thẻ
**Friends tab**; nếu host chỉ trả lời `RequestPacket` thì client thuần listener không bao giờ
thấy world. `xvrelay -listen-nethernet` phải **chủ động phát `ResponsePacket`** như host vanilla.

```text
client Bedrock LAN (self-signed, không Xbox)
  -> UDP 7551 discovery: ResponsePacket broadcast 255.255.255.255 / 192.168.1.255
  -> signaling UDP 192.168.1.219:8555 (CONNECTREQUEST/CONNECTRESPONSE)
  -> WebRTC/ICE/DTLS/SCTP (port ephemeral, rule firewall theo program xvrelay.exe)
  -> xvrelay -listen-nethernet -host-raknet 127.0.0.1:19132   [client 2193 <-> host 2225]
  -> Geyser 2.12.1 (UDP RakNet 19132) -> ViaProxy 25566 -> Java 1.21.11 25565
```

Lệnh chạy cuối (còn sống, PID 18868 → bản restart 23:36):

```bash
./dist/xvrelay.exe -listen-nethernet -host-raknet 127.0.0.1:19132 \
  -lan-signal-ip 192.168.1.219 -lan-signal-port 8555 \
  -lan-broadcast "255.255.255.255:7551,192.168.1.255:7551" \
  -lan-name mpmc-Java-Gateway -lan-level mpmc-Java-1.21.11 \
  -lan-proto 2225 -lan-version 1.26.60 -lan-offline-auth \
  -client-proto auto -host-proto 2225 -trace 20
```

Thay đổi source (repo `bedrock-cross-version-relay`, commit local, chưa push):

| File | Thay đổi |
|---|---|
| `internal/lansignal/lansignal.go` | `Options.Announce` / `Advertise` / `Broadcasts`; `SetAdvertisement()` pin binary `ServerData`, chặn `PongData()` ghi đè; `announceLoop()` phát `ResponsePacket` mỗi 2 s; `listenShared()` + `SO_REUSEADDR` để chia sẻ 7551 với Overlix (PID 20844) mà không kill nó |
| `cmd/xvrelay/main.go` | `-lan-broadcast`, `-lan-signal-ip`, `-lan-signal-port`, `-lan-offline-auth`, `lanWorldCard()`; `AcceptsOnlineAuth=false`, `AcceptsSelfSignedAuth=true`, `ConnectionType=4` |
| `third_party/gophertunnel-2225/.../login/data.go` | `ClientData.Validate()` chấp nhận `ServerAddress` là **network ID thập phân** của NetherNet LAN (trước đó đòi `host:port` ⇒ đóng conn ngay sau Login) |
| `internal/blocktranslate/registry.go` | `configureBlockTranslation` degrade custom block Geyser khi `hashed=false` thay vì `return error` giết session; vẫn dịch 23960 vanilla state |
| `cmd/xvrelay/lan_login_test.go` | 4 regression test (numeric ID / garbage / host:port / Geyser custom blocks) |
| `internal/lansignal/announce_test.go` | test `Announce` + `ServerData` |

**Bằng chứng wire-level** (`bedrock-cross-version-relay/dist/lan255-capture.txt`, raw socket
`SIO_RCVALL` trên `192.168.1.219`, 8 s): **110** gói UDP/7551, trong đó **16** gói từ
`192.168.1.219:8555 → 255.255.255.255:7551` và **16** gói `→ 192.168.1.255:7551`. Giải mã
đúng HMAC-SHA256 + AES-256-ECB (key `SHA256(LE 0xdeadbeef)`), `packet_id=1`, `ServerData`
`version=7`, `server_name=mpmc-Java-Gateway`, `protocol=2225`, `version=1.26.60`,
`level_name=mpmc-Java-1.21.11`, `0/20`, **`accepts_online_auth=False`**,
**`accepts_self_signed_auth=True`**, `connection_type=4`, `nonce=""`, `unread=0`.
(52 gói `192.168.1.255` còn lại là `RequestPacket` id=0 từ Overlix `:8554` — để nguyên.)

**Bằng chứng join thật** (`/tmp/joiner-final-auto.log`, `dist/xvrelay-lan-final.log`,
`_m6/geyser/java-server.log`): joiner thấy
`self_signed=true online=false announced=true answered=true`, tự thử protocol 2193,
`Spawn hoàn tất`, chat `1/1` được server phản hồi. Java server ghi
`LANJoinerFinal joined the game` (23:39:48) rồi
`[Not Secure] <LANJoinerFinal> hello-from-LAN-offline` (23:39:50). Geyser ghi
`Player connected with username LANJoinerFinal (2225)`. Relay: 189 gói H→C
(118 `LevelChunk`), `skipped=0`, `level=ERROR=0`, `ItemRegistry replaced 2080/2088`,
block palette 23960 state + 30 placeholder, bỏ 105 custom block có kiểm soát.

Hồi quy: `CGO_ENABLED=0 go test -buildvcs=false ./internal/lansignal/...
./internal/blocktranslate/... ./cmd/xvrelay/... ./cmd/clientclone/...` ⇒ **4/4 `ok`**.
Binary `dist/xvrelay` và `dist/xvrelay.exe` cùng SHA `941a5ab3…` (kiểm trước restart,
tránh bẫy `.exe` cũ ở luật #12). Firewall inbound: `MPMC LAN Discovery UDP 7551`,
`MPMC LAN Signaling UDP 8555`, `MPMC LAN WebRTC xvrelay` (theo program, mọi port ephemeral),
`xvrelay UDP 19164`, `MPMC Geyser … 19132/19134`, tất cả `RemoteAddress=LocalSubnet`.

**Còn `[Chưa xác minh]`**: client Bedrock **đồ hoạ gốc từ thiết bị LAN thứ hai** (chưa có
thiết bị để test); đường NetherNet với **protocol 2225** đúng version card (terminal test client
`joiner_new.exe` không có codec 2225, báo `unsupported LAN protocol 2225` — 2225 mới chỉ đo
qua RakNet bằng `clientclone` ở §10.1); prompt **TOFU** lần join đầu; WAN / qua router.
`-lan-proto`/`-lan-version` có thể đổi nếu client thật dùng version khác — relay vẫn chấp
nhận `1001/2168/2169/2193/2225` trên wire. §0 giữ nguyên: không đụng agent, không nhúng
GPL vào `mpmc-agent-*.zip`, gateway agent vẫn bind loopback.

