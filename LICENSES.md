# License notes

- Mã agent (Kotlin/Java, script, docs) trong repo này là mã nội bộ chưa công khai;
  repo được tạo ở chế độ **private**, chưa gán license công khai.
- `gateway-patches/` chứa source và diff của **ViaBedrock** và **ViaProxy**, các dự án
  **GPL-3.0** của ViaVersionAddons / ViaVersion. Chúng được giữ tách biệt, chỉ phục vụ
  tái dựng bản build; KHÔNG được nhúng vào `mpmc-agent-*.zip` (xem §0 `docs/M6-BEDROCK.md`).
- `third_party/` trong repo relay liên quan có license riêng của từng dự án upstream.
- Không có secret/token nào được đưa vào repo này.
