# MEASUREMENTS — M0 spike (iroh JVM binding)

Ngày đo: 2026-11-10 · Máy: Windows 11 x64 · JDK 21.0.12.1 (Temurin) · Gradle 8.13 · Kotlin 2.2.21
Binding: `computer.iroh:iroh:1.2.0` + JNA 5.17.0 + kotlinx-coroutines 1.9.0 (jar 25,3 MB, native win32-x64 trong jar)

Lệnh chạy spike:

```
cd m0-spike
./gradlew.bat installDist
build/install/m0-spike/bin/m0-spike.bat listen [--relay n0|disabled|custom:<url>] [--bind <ip:port>]
build/install/m0-spike/bin/m0-spike.bat dial <endpoint-id> [--relay ...] [--direct <ip:port>] [--relay-url <url>] [--timeout <s>]
```

`heap_mb` = heap đã dùng; `committed_mb` = committed virtual memory của JVM (theo OS, gồm cả vùng native) — **không phải RSS thật**, RSS native `[Chưa xác minh]`.

## Test 1 — N0 (discovery + relay công cộng, dial id-only — đúng luồng production)

| Chỉ số | Giá trị | Ghi chú |
|---|---|---|
| bind (listen / dial) | 126 ms / 61 ms | đo quanh `EndpointBuilder.bind()` |
| connect | ok | id-only `EndpointAddr(id, null, [])`, iroh tự resolve qua n0 discovery |
| paths trước (dial) | 1 path relay, sel=true, rtt 225 ms, `aps1-1.relay.n0.iroh.link` | kết nối thiết lập **qua relay** |
| gửi 1 MiB (dial) | 12 ms | `writeAll` + `finish` |
| echo 1 MiB (listen) | 71 ms ≈ 14,08 MiB/s | đọc–ghi–finish, **đúng từng byte** (`echo_ok=true`) |
| paths sau | relay rtt 154 ms (không chọn) + **direct sel=true rtt 9 ms** (`172.31.80.1`) | iroh tự chuyển sang direct giữa chừng |
| bộ nhớ (dial / listen) | heap 6/22 MB, committed 358/354 MB | |
| đóng | closed=ok, shutdown=ok, exit 0 | cả 2 process |

## Test 2 — Offline (relay disabled, direct addr truyền tay)

| Chỉ số | Giá trị |
|---|---|
| bind (listen / dial) | 98 ms / 80 ms |
| paths | 1 path direct (`ip=true,relay=false`), rtt 1–16 ms |
| gửi / echo 1 MiB | 23 ms / 72 ms ≈ 13,89 MiB/s, đúng từng byte |
| đóng | closed=ok, shutdown=ok, exit 0 |

## Test 3 — Echo thuần qua relay (relay cục bộ iroh-relay 1.2.0, dial kèm relay URL, không direct)

Relay chạy `iroh-relay.exe --dev` (HTTP/WS trên `[::]:3340`, QUIC=không). Cả 2 đầu dùng `--relay custom:http://localhost:3340`.

| Chỉ số | Giá trị |
|---|---|
| bind (listen / dial) | 62 ms / 45 ms |
| paths trước | **1 path relay sel=true**, rtt 175 ms — không có ứng viên direct nào |
| gửi / echo 1 MiB | 12 ms / 62 ms ≈ 16,13 MiB/s, đúng từng byte |
| paths sau | relay (153 ms) + direct mới được học qua handshake relay (rtt 8 ms, 127.0.0.1) |
| đóng | closed=ok, shutdown=ok, exit 0 |

Kết luận: đường dữ liệu relayed (đầu→relay→đầu) chỉ cần TCP/WS tới relay, **không cần UDP trực tiếp giữa 2 peer** — cơ sở cho gate "block UDP vẫn echo qua relay". Trường hợp thật (2 máy, UDP bị chặn hoàn toàn) `[Chưa xác minh]`.

## Phát hiện quan trọng cho M1

1. **Preset bắt buộc để cài rustls crypto provider.** `EndpointBuilder()` trống + `relayMode(...)` → `bind()` lỗi `Missing or incompatible rustls crypto provider configured`. Phải gọi `applyN0()` / `applyMinimal()` / `applyN0DisableRelay()` trước khi set relay mode. Thứ tự đúng: preset → `relayMode(...)` → `alpns(...)` → `bind()`. (Kiểm chứng trong source `n0-computer/iroh-ffi` tag v1.2.0: crypto provider chỉ được cài trong các preset.)
2. **Dial id-only không tự đi qua home relay khi dùng relay custom mà không có discovery.** Test `RelayMode.customFromUrls` + dial id-only (không `--relay-url`): 2 đầu đều kết nối WS tới relay thành công (log relay: `relay_connection upgraded`) nhưng `connect()` timeout — iroh 1.2 không resolve peer qua home relay nếu không có discovery. Với N0 (có discovery) thì id-only hoạt động bình thường (Test 1).
   → **Quyết định M1**: trong transport-iroh, khi `RelayMode.Custom`, `dial()` phải truyền relay URL của phòng (từ `relayUrls` trong response `POST /v1/rooms`) vào `EndpointAddr(id, relayUrl, [])` — đúng tinh thần prompt §6.2 (coordinator trả `relayUrls`), nhưng lệch với câu "không truyền relay URL của host" trong §3 (câu đó nhằm tránh truyền địa chỉ direct cũ từ ticket; relay URL hiện hành từ coordinator thì an toàn). Cần xác nhận lại khi viết M1.
3. **Khớp phiên bản relay:** binding iroh core 1.2.0 chạy với iroh-relay 1.2.0 (transport WS trên cổng HTTP). Relay 1.4.1 (WS) `[Chưa xác minh]` cho routing — dùng 1.2.0 cho đến khi nâng binding.
4. **Relay dev-mode trên Windows bind `[::]:3340` v6-only:** không reach được qua `http://127.0.0.1:3340` (chỉ `localhost`/`[::1]`). Khi triển khai relay thật cần config bind rõ ràng.
5. `RecvStream.read(len: UInt): ByteArray?` trả `null` ở EOF; `SendStream.finish()` half-close; `Connection.close(0L, byteArrayOf())`; `paths()` không suspend; `EndpointId.fromString(hex64)`.

## Gate M0

| Hạng mục | Kết quả |
|---|---|
| Native load trên Win11 x64 (JNA từ jar) | PASS |
| 2 process echo đúng 1 MiB không mất byte | PASS ×3 (N0 / offline / relay) |
| `paths()` ≥ 1 path, phân loại direct/relay đúng | PASS (cả 2 loại quan sát được, kèm chuyển relay→direct) |
| Echo qua relay (không cần UDP trực tiếp peer) | PASS (Test 3, relay thuần) — môi trường 2 máy + chặn UDP thật: `[Chưa xác minh]` |
| Không crash khi đóng endpoint | PASS (6 process, exit 0) |

## Chưa xác minh

- Throughput/RSS trên mạng thật (2 máy, NAT thật) — mọi số đo trên là cùng 1 máy.
- Độ trễ bind/connect qua mạng thật; connect_ms chưa đo riêng trong spike.
- Tương thích relay 1.4.1, iroh-services discovery, `applyN0DisableRelay`.
- Hành vi khi relay công cộng N0 không reach được (firewall chặn 443/NAT cứng).

# MEASUREMENTS — M5 (Minecraft thật + tải)

Ngày đo: 2026-11-10 · Windows 11 x64 · Intel Core i5-12450H (8C/12T) · RAM 15,7 GB · JDK 21.0.12.1

## Thiết lập

- Server: Minecraft vanilla **1.21.11**, `online-mode=false`, flat world, `view-distance=4`,
  `simulation-distance=4`, `-Xms512M -Xmx1024M`.
- Client: `mineflayer` 4.39.0 (hỗ trợ protocol 1.21.11), auth offline, mỗi lượt đăng nhập, spawn,
  nhận chat, đi 1 s rồi thoát.
- Agent: host + join cùng máy, `relay_mode=disabled`, đường **direct loopback**; `JAVA_OPTS=-Xms64m -Xmx512m`
  cho cả hai agent (gói phát hành đặt mặc định này).
- Server, 2 agent, client đều chạy trên cùng máy; số đo phản ánh CPU/băng thông nội bộ, **không phải**
  mạng thật giữa hai máy.

## Đăng nhập Minecraft thật (3 lượt mỗi cấu hình, giây)

| Đường | login min/median/max | spawn min/median/max | Thành công |
|---|---|---|---|
| Direct 127.0.0.1:25565 | 577 / 678 / 840 ms | 601 / 690 / 856 ms | 3/3 |
| Qua tunnel 127.0.0.1:25566 | 567 / 683 / 742 ms | 577 / 697 / 753 ms | 3/3 |

Chênh lệch nằm trong nhiễu khởi động của client; tunnel không làm chậm đăng nhập trên loopback.

## Nhiều client đồng thời (cùng lúc, mỗi client chơi 5 s)

| Số client | Thành công | login trung bình | spawn trung bình | Tổng thời gian tường |
|---|---|---|---|---|
| 1 | 1/1 | 663 ms | 680 ms | 7,2 s |
| 5 | 5/5 | 803 ms | 822 ms | 8,4 s |
| 10 | 10/10 | 931 ms | 954 ms | 9,7 s |

Mỗi client dùng một stream iroh riêng; 10 client đồng thời không bị từ chối (giới hạn mặc định 20
stream/phòng, 4 stream/vé ở production).

## Throughput echo 50 MiB mỗi stream

TCP echo cục bộ ở đích host; client ghi 50 MiB và đọc lại đúng 50 MiB.

| Số stream | Tổng thời gian | Tổng MiB/s | MiB/s mỗi stream |
|---|---|---|---|
| 1 | 2,6–3,8 s | 13,2–19,1 | 13,2–19,1 |
| 5 | 22,2–24,2 s | 10,3–11,3 | 2,1–2,3 |
| 10 | 84,9 s | 5,9 | 0,6 |

Ghi chú: một lần chạy 10×50 MiB trước khi giới hạn heap bị treo ở 7/10 stream (không lỗi, không tiến
triển) và phải dừng sau ~2 phút. Sau khi đặt `-Xmx512m` và khởi động lại agent, 10/10 stream hoàn tất.
Đây là điểm cần điều tra thêm ở tải bulk cực đại; lưu lượng Minecraft 10 client không gặp hiện tượng này.

## CPU / RAM agent (WorkingSet, chuẩn hoá 1 lõi logic)

| Kịch bản | host CPU | join CPU | host RSS | join RSS |
|---|---|---|---|---|
| 50 MiB, 1 stream | 42,2 % | 45,6 % | 113 MB | 116 MB |
| 50 MiB × 5 stream | 104,3 % | 93,6 % | 153 MB | 148 MB |
| 50 MiB × 10 stream | 106,7 % | 121,1 % | 201 MB | 173 MB |
| 10 client Minecraft (cả phiên đo) | 9,0 % | 7,7 % | 126 MB | 126 MB |

Minecraft server cùng lúc: ~31,5 % CPU, ~715 MB RSS (chỉ để tham chiếu; không phải chi phí của mpmc).

`CPU %` = chênh lệch `Get-Process.CPU` / thời gian tường × 100, tức % của **một** lõi; máy có 12 lõi
logic. RSS gồm heap JVM + bộ nhớ native của iroh. Trước khi giới hạn heap, một lần chuyển 50 MiB làm
JVM phình lên ~800 MB và không trả lại; vì vậy gói phát hành đặt `-Xmx512m`.

## Setup / đường truyền

- `setupMs` (dial + HELLO + HELLO_OK) trên loopback direct: **132–191 ms** (session.json).
- `rttMs` path direct loopback: **4–13 ms**; khi 5 stream bulk chạy, RTT path tăng tới ~300 ms do
  tranh chấp CPU/băng thông (đo được, không phải số liệu mạng thật).

## M6 — Java client → server Bedrock (một máy, 2026-11-10)

Chuỗi: bot Java 1.21.11 (mineflayer) → join agent `127.0.0.1:25566` → iroh direct → host agent →
ViaProxy 3.4.14 (`127.0.0.1:25568`) → BDS 1.26.51.1 RakNet `127.0.0.1:19132`.

| Kịch bản | login | spawn | toạ độ lúc spawn | health/food | byte tunnel (up/down) | phiên |
|---|---|---|---|---|---|---|
| ViaProxy thủ công, không tunnel | 3036 ms | 3060 ms | 0,5 / -60 / 1,27 | 20 / 20 | — | — |
| Qua mpmc, gateway thủ công | 1319 ms | 1324 ms | 0,5 / -60 / 3 | 20 / 20 | 73 523 / 1 274 | 14,3 s |
| Qua mpmc, `host --bedrock` (CLI quản lý) | 1393 ms | 1398 ms | 0,5 / -60 / 1,92 | 20 / 20 | 70 831 / 1 041 | 12,4 s |

- Mỗi kịch bản chạy **một lần**; chênh lệch login giữa các lần không đủ để kết luận thống kê.
- Gateway do mpmc khởi động: ViaProxy bắt đầu 20:24:07, bind cổng 20:24:07, host in endpoint 20:24:09
  → sẵn sàng trong khoảng **2 s** (một lần đo).
- Sau khi host dừng bằng lệnh điều khiển `stop`, cổng 25568 đóng — gateway con dừng theo.
- Byte `up/down` lấy từ log `stream_closed` của HostService; đây là byte của luồng Java, chưa gồm lưu
  lượng RakNet giữa ViaProxy và BDS (không đi qua tunnel).

## Chưa xác minh

- Client Minecraft chính thức có đăng nhập Microsoft + server `online-mode=true`.
- Hai máy khác mạng/NAT thật, relay TLS thật, Wi-Fi/latency cao.
- Throughput 10 stream bulk với heap lớn/không giới hạn (hiện tượng treo đã ghi ở trên).
- RSS native tách riêng khỏi heap JVM.
- Bedrock qua tunnel trên hai máy/NAT thật; client Microsoft auth; đích Bedrock khác 1.26.51; client
  Bedrock gốc; độ ổn định phiên dài (các test M6 chỉ giữ 10–14 s).
