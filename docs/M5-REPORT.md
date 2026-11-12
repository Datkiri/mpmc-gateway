# M5 — Minecraft thật, đóng gói, đo lường (báo cáo kết thúc MVP)

Ngày: 2026-11-10 · Phạm vi: M5 là milestone cuối; MVP dừng ở đây theo §0 (không Android/GUI/friends/
host migration).

## 1. Mục tiêu M5 và kết quả

| Hạng mục | Trạng thái | Bằng chứng |
|---|---|---|
| Minecraft Java thật chạy qua tunnel | Đạt | vanilla 1.21.11 (offline-mode), `mineflayer` 4.39.0: login → spawn → chat → di chuyển |
| Nhiều client đồng thời | Đạt | 1/5/10 bot cùng lúc, 10/10 thành công, không stream nào bị từ chối |
| Đo lường trung thực | Đạt | [MEASUREMENTS.md](MEASUREMENTS.md) mục M5: login, concurrency, throughput, CPU/RAM, setupMs/RTT |
| Đóng gói chạy được | Đạt | `build/dist/mpmc-agent-0.1.0.zip`; smoke-test `run-host.bat`/`run-join.bat` từ thư mục giải nén |
| Triển khai Linux | Đạt (chưa chạy trên VPS thật) | systemd unit `packaging/mpmc-join.service`, env example, [RUNBOOK.md](RUNBOOK.md) |
| Sửa lỗi phát hiện khi test thật | Đạt | join không cập nhật `session.json` sau khi nối; host không cộng byte chiều xuống |

## 2. Thay đổi mã nguồn trong M5

- `agent-core/.../JoinService.kt`: thêm ticker 2 s tái báo `SessionState` khi đã kết nối, lưu `setupMs`
  một lần; nhờ đó `bytesUp`, `bytesDown`, `openTcp`, `rttMs` trong `session.json` phản ánh lưu lượng
  thật thay vì đứng ở 0.
- `agent-core/.../HostService.kt`: meter toàn cục cộng cả chiều xuống (`addDown`), trước đó chỉ cộng
  chiều lên nên báo cáo byte bị lệch.
- `agent-cli/.../JoinCommand.kt`: nhận `MPMC_ROOM_CODE`, `MPMC_INVITE_TOKEN` từ biến môi trường để
  không lộ mã phòng/token trong danh sách tiến trình.
- `build.gradle.kts`: task `packageAgent` tạo zip phát hành kèm script, unit systemd, config mẫu, docs.
- `packaging/`: `run-host.sh|bat`, `run-join.sh|bat` (mặc định `-Xms64m -Xmx512m`, tôn trọng
  `MPMC_DATA_DIR`), `mpmc-join.service`, `join.env.example`, `config.example.toml`.
- `docs/RUNBOOK.md`, `docs/MEASUREMENTS.md` (mục M5).

## 3. Kiểm thử

| Bộ | Số test | Kết quả |
|---|---|---|
| `protocol` (unit) | 24 | pass |
| `coordinator` (unit + IT embedded PG) | 21 | pass |
| `it` (TunnelIT, AuthIT, RelayIT, CliProcessIT) | 18 | pass |
| `transport-iroh` | 1 | pass |
| Tổng | 64 | **BUILD SUCCESSFUL** (`./gradlew.bat test :it:test`) |

Kiểm thử thủ công ngoài Gradle: server Minecraft thật + bot thật, echo TCP 50 MiB, đo CPU/RAM bằng
PowerShell; kịch bản nằm trong `_m5/` (giữ lại để chạy lại, không đóng gói phát hành).

## 4. Tóm tắt số đo (chi tiết trong MEASUREMENTS.md)

- Login qua tunnel so với direct loopback: median 683 ms vs 678 ms — không chênh lệch đáng kể.
- 10 client đồng thời: 10/10, login trung bình 931 ms.
- Echo 50 MiB: 1 stream 13,2–19,1 MiB/s; 5 stream tổng 10,3–11,3 MiB/s; 10 stream tổng 5,9 MiB/s.
- Agent 10 client Minecraft: ~9 % một lõi, ~126 MB WorkingSet mỗi đầu.
- `setupMs` 132–191 ms trên loopback direct.
- Có một lần treo 10×50 MiB ở 7/10 trước khi giới hạn heap; sau `-Xmx512m` hoàn tất — ghi nhận là
  điểm cần điều tra ở tải bulk cực đại, không tái hiện với lưu lượng 10 client Minecraft.

## 5. Giới hạn đã biết / chưa xác minh

- Mọi số đo trên **một máy**; chưa đo hai máy khác mạng, NAT thật, relay TLS thật, Wi-Fi/latency cao.
- Chưa test client chính thức có Microsoft auth + server `online-mode=true` (rào cản §0.2: không tắt
  online-mode, tunnel thuần byte nên về lý thuyết không ảnh hưởng — **chưa xác minh**).
- Throughput bulk 10 stream còn phụ thuộc heap; chưa tối ưu buffer/backpressure.
- RSS native của iroh chưa tách khỏi heap JVM.
- Systemd unit chưa chạy trên máy Linux thật/VPS.

## 6. Kết luận

MVP Java↔Java P2P hoàn tất: coordinator + auth/ticket, tunnel iroh với direct/relay, CLI host/join,
relay cục bộ + fallback, doctor, đóng gói và runbook. Điều kiện biên §0 được giữ nguyên trong toàn bộ
quá trình: tunnel chỉ chuyển byte sau `HELLO`, không sửa gói Minecraft, không tắt online-mode, proxy
chỉ bind `127.0.0.1`, join không chọn được đích TCP, không log bí mật.
