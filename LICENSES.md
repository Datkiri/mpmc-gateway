# License notes

- Repo **public** từ 13/11. Mã agent (Kotlin/Java, script, docs) là mã nội bộ,
  **chưa gán license công khai** ⇒ mặc định all-rights-reserved; việc public chỉ để
  chia sẻ/báo cáo, không phải cấp phép sử dụng lại.
- `gateway-patches/` chứa source và diff của **ViaBedrock** và **ViaProxy**, các dự án
  **GPL-3.0** của ViaVersionAddons / ViaVersion. Chúng được giữ tách biệt, chỉ phục vụ
  tái dựng bản build; KHÔNG được nhúng vào `mpmc-agent-*.zip` (xem §0 `docs/M6-BEDROCK.md`).
  Vì repo đã public, cần bổ sung đầy đủ LICENSE/NOTICE GPL-3.0 và giữ nguyên header
  bản quyền upstream nếu tiếp tục phân phối source này — **[Chưa làm]**.
- `third_party/` (branch `xver-relay`) và `java/lib/*.jar` có license riêng của từng
  dự án upstream; đã rà 25 commit, không có token/secret.
- Không có secret/token nào được đưa vào repo này (`join.env.example` có
  `MPMC_INVITE_TOKEN=` rỗng, `geyser-config.yml` có `token`/`password`/`private-key` rỗng).
