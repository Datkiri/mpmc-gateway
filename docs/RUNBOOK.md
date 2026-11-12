# RUNBOOK — vận hành mpmc (M1–M6)

Tài liệu này dành cho người triển khai. Kiến trúc: Minecraft client → **join agent** (127.0.0.1) →
iroh (direct hoặc relay) → **host agent** → Minecraft server. Coordinator cấp phòng, duyệt join và phát
vé; relay chỉ chuyển byte đã mã hoá. Agent **không** đọc/sửa gói Minecraft và **không** tắt
`online-mode`.

## 1. Chuẩn bị

- JDK 21+ trên máy host và máy join.
- Coordinator đã chạy (PostgreSQL + Ktor) và relay đã chạy nếu không dùng được direct.
- Mở outbound 443/tcp (coordinator, relay) và UDP cho iroh; không cần mở cổng vào.
- Tải gói agent:

```bash
./gradlew.bat :packageAgent
# build/dist/mpmc-agent-0.1.0.zip
unzip mpmc-agent-0.1.0.zip -d /opt
chmod +x /opt/mpmc/run-*.sh /opt/mpmc/bin/mpmc
```

Gói gồm: `bin/`, `lib/`, `run-host.*`, `run-join.*`, `config.example.toml`, `mpmc-join.service`,
`join.env.example`, `docs/`.

## 2. Cấu hình agent

Sao chép `config.example.toml` thành `<data-dir>/config.toml`:

```toml
[coordinator]
url = "https://coordinator.example.com"

[transport]
relay_mode = "custom"
relay_urls = ["https://relay.example.com"]

[host]
target = "127.0.0.1:25565"
approval = "prompt"

[join]
listen = "127.0.0.1:25566"
```

- `coordinator.url` phải là `https://` (chỉ chấp nhận `http://` với loopback khi dev).
- `join.listen` bắt buộc bắt đầu bằng `127.0.0.1:` — agent từ chối bind ra ngoài.
- `host.target` là đích duy nhất host được phép dial; join không chọn được đích.
- `relay_mode`: `custom` (relay riêng), `n0` (relay công cộng), `disabled` (chỉ LAN/direct).

Kiểm tra môi trường:

```bash
/opt/mpmc/bin/mpmc doctor --data-dir /var/lib/mpmc
```

`PASS` mới là bắt buộc; `WARN` cần đọc lý do. `relay-udp` luôn `WARN` vì UDP không bắt tay được.

## 3. Chạy host (máy có Minecraft server)

```bash
export MPMC_DATA_DIR=/var/lib/mpmc
/opt/mpmc/run-host.sh
```

Agent in ra mã phòng (`Phòng: ABC-123`) và endpoint id. Giữ process chạy. Khi người lạ xin vào, agent
hỏi `[y/N]` nếu `approval = "prompt"`; dùng `--approve-all` chỉ khi đã có kênh tin cậy khác.

Windows: `run-host.bat`.

## 4. Chạy join (máy chơi Minecraft)

Cách an toàn nhất là để host duyệt tay, không cần chia sẻ invite token:

```bash
MPMC_ROOM_CODE=ABC-123 /opt/mpmc/run-join.sh
```

Nếu host dùng chế độ `invite`, đặt token trong biến môi trường (không truyền qua dòng lệnh để tránh lộ
trong `ps`):

```bash
export MPMC_INVITE_TOKEN=...
export MPMC_ROOM_CODE=ABC-123
/opt/mpmc/run-join.sh
```

Khi join in `Sẵn sàng: 127.0.0.1:25566`, trong Minecraft thêm server `127.0.0.1:25566`.
Không bind ra `0.0.0.0`; không chia sẻ địa chỉ này cho máy khác.

### systemd (join)

```bash
sudo useradd --system --home /opt/mpmc --shell /usr/sbin/nologin mpmc
sudo install -d -o mpmc -g mpmc /var/lib/mpmc /etc/mpmc
sudo cp /opt/mpmc/mpmc-join.service /etc/systemd/system/
sudo cp /opt/mpmc/join.env.example /etc/mpmc/join.env
sudo chmod 600 /etc/mpmc/join.env && sudo chown mpmc:mpmc /etc/mpmc/join.env
sudo systemctl daemon-reload && sudo systemctl enable --now mpmc-join
```

`join.env` giữ `MPMC_ROOM_CODE`, `MPMC_INVITE_TOKEN`; unit không chứa secret. Unit bật
`NoNewPrivileges`, `ProtectSystem=strict`, `ProtectHome`, chỉ ghi được `/var/lib/mpmc`.

## 5. Host server Bedrock (tuỳ chọn)

Áp dụng khi server đích là **Bedrock Dedicated Server** và người chơi dùng client Java. mpmc chạy
ViaProxy (GPL-3.0) như tiến trình con phía host; tunnel vẫn chỉ chuyển byte.

```bash
/opt/mpmc/bin/mpmc gateway install     # tải + kiểm SHA-256 (một lần)
/opt/mpmc/bin/mpmc gateway status
/opt/mpmc/bin/mpmc doctor --bedrock 127.0.0.1:19132
```

Để lấy đồ creative và đặt/đào block trên Bedrock 1.26.40+, dùng bản gateway `mpmc-patched` (build
GPL ngoài gói phát hành, xem `docs/M6-BEDROCK.md` mục 5.2 và 6):

```bash
/opt/mpmc/bin/mpmc gateway install --from /path/to/ViaProxy-3.4.14.jar
/opt/mpmc/bin/mpmc gateway status     # phải ghi "mpmc-patched"; hash khác bị từ chối
```

Máy host chạy BDS RakNet (mặc định UDP 19132) và:

```bash
MPMC_DATA_DIR=/var/lib/mpmc /opt/mpmc/bin/mpmc host --bedrock 127.0.0.1:19132 --gateway-bind 127.0.0.1:25568
```

- `--bedrock` thay cho `--target`; không dùng đồng thời cả hai.
- `--gateway-bind` bắt buộc là loopback; `--bedrock-version` mặc định `Bedrock 1.26.51` và phải có
  trong `java -jar ViaProxy-3.4.14.jar cli --list-versions`.
- Gateway sẵn sàng thì host mới in endpoint; khi host dừng, gateway cũng dừng.
- Log gateway: `<data-dir>/gateway/viaproxy.log` (có thể chứa IP/username người chơi; không chia sẻ).
- Phía join và client Java **không đổi**: kết nối `127.0.0.1:25566` như mục 4.
- Không nhúng jar ViaProxy vào gói phát hành; bản chính thức tải từ release đã pin SHA-256, bản
  `mpmc-patched` cài từ tệp GPL build ngoài (hash cũng phải khớp pin).

Chi tiết, phiên bản đã kiểm chứng và giới hạn: `docs/M6-BEDROCK.md`.

## 6. Kiểm chứng

1. `mpmc status --data-dir <data-dir>` hoặc đọc `<data-dir>/session.json`:
   `state=connected`, `path=direct|relay|mixed`, `local_address=127.0.0.1:25566`.
2. Vào Minecraft, đăng nhập server; kiểm tra log server có `joined the game`.
3. `mpmc doctor` trên cả hai máy khi có sự cố.
4. Log agent dạng JSON; các mã lỗi thường gặp:
   - `RELAY_UNAVAILABLE`: không có direct address và TCP tới relay thất bại.
   - `HOST_OFFLINE`: dial thất bại sau 5 lần backoff.
   - `NOT_A_MEMBER`: host từ chối hoặc endpoint bị kick.
   - `ROOM_CLOSED`: phòng đã đóng/hết hạn.
   - `TICKET_EXPIRED`: vé hết hạn; join tự xin vé mới một lần.

## 7. Quy trình test thật (đã chạy)

M5 dùng Minecraft Java 1.21.11 (vanilla server, `online-mode=false`) và client tự động `mineflayer`:

```bash
java -Xms512M -Xmx1024M -jar server.jar nogui        # server offline-mode
node run-matrix.js all                               # direct vs tunnel, 1/5/10 client
```

Kết quả và số đo: `docs/MEASUREMENTS.md`. Với client Minecraft thật có đăng nhập Microsoft và server
`online-mode=true`, tunnel vẫn chỉ chuyển byte nên về lý thuyết không ảnh hưởng; **chưa xác minh** bằng
tài khoản thật trong môi trường này.

## 8. Bảo mật

- Không log `Authorization`, `hostToken`, `inviteToken`, vé hay join ticket.
- `hostToken` chỉ đi trong header `X-Mpmc-Host-Token`.
- Mã phòng không đủ để vào: cần invite token **hoặc** host duyệt; vé 120 s gắn endpoint id.
- Relay thấy endpoint id và lưu lượng đã mã hoá; không thấy nội dung Minecraft.
- Bedrock: ViaProxy là tiến trình ngoài (GPL-3.0) chỉ bind loopback; log của nó có thể chứa IP và tên
  người chơi. Không đưa `gateway/viaproxy.log` vào báo cáo hay chia sẻ công khai.
- Không chạy agent bằng quyền admin/root; unit systemd đã bỏ toàn bộ capability.

## 9. Giới hạn đã biết

- Relay mới kiểm chứng cục bộ, chưa đo trên VPS/TLS thật.
- Throughput tổng quanh 1 lõi CPU/agent (xem MEASUREMENTS); nhiều stream đồng thời chia sẻ băng thông.
- Chưa có GUI/Android/friends/host migration (ngoài phạm vi M5).
- `updateRelay` chưa hỗ trợ đổi relay khi đang chạy; phải khởi động lại agent.
- Bedrock: mới xác minh đích `Bedrock 1.26.51` với ViaProxy 3.4.14; chưa test Bedrock 1.26.60/2225,
  client Microsoft auth, hai máy khác mạng, hoặc client Bedrock gốc.
