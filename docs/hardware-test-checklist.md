# Phiếu làm kiểm thử phần cứng H1–H10

In ra mang theo khi làm với ESP32 thật. Mỗi ca ghi: thao tác, điều phải thấy, số liệu cần ghi và ảnh/video cần chụp.
Làm xong điền vào [`hardware-test-results.md`](hardware-test-results.md); ảnh, video lưu ở `docs/images/tests/` với
tên gợi ý trong phiếu. Định nghĩa ca kiểm thử: [`test-plan.md`](test-plan.md) mục 6.

Thứ tự trong phiếu khác thứ tự mã H để đỡ phải chờ: các ca cần MQ-2 đã nóng làm trước, các ca làm ESP32 khởi động
lại làm sau cùng. Tổng thời gian khoảng 45–60 phút.

## 0. Chuẩn bị

- [ ] Hệ thống chạy: `dc ps` có 4 container `Up`; web mở được `https://<IP>:8443`.
- [ ] Serial Monitor 115200 baud mở cạnh trình duyệt (chụp được cả hai trong một ảnh càng tốt).
- [ ] Điện thoại để quay video (H3, H5, H10).
- [ ] Bật lửa gas (chỉ xịt, **không đánh lửa**), cốc nước nóng khoảng 60 °C (chỉ dùng với đầu DS18B20 loại chống nước).
- [ ] Email nhận cảnh báo đã xác nhận trên trang **Tài khoản**.
- [ ] Cảnh báo cũ đã xử lý hết; cấu hình: chu kỳ 5 s, ngưỡng 400 ppm, tự bật quạt; quạt và còi ở **Tự động**.
- [ ] Không để `.env`, `secrets.h`, mật khẩu xuất hiện trong ảnh hay video.

Ghi chung (đầu file kết quả): ngày, người thực hiện, mã thiết bị, mạng WiFi (tên mạng không cần ghi mật khẩu), IP máy chủ.

## 1. Các ca kiểm thử

### H1 – Kích hoạt thiết bị thật

- **Thao tác:** web → **+ Đăng ký thiết bị** → tab **Xác thực MQTT** → **Cấp thông tin xác thực** → chép vào
  `secrets.h` → nạp → cấp nguồn. (Nếu thiết bị đã kích hoạt từ trước: đăng ký một mã mới để làm ca này.)
- **Phải thấy:**
  - [ ] [Serial] `Đã đồng bộ giờ NTP` → `Kết nối MQTT (TLS) …` → `Đã kết nối broker`.
  - [ ] Web: **Đã đăng ký** → **Đã cấp quyền** → **Hoạt động** + **Trực tuyến**, firmware `1.0.0`.
  - [ ] Tab **Lịch sử hoạt động**: `DEVICE_REGISTER`, `DEVICE_PROVISION`, `DEVICE_AUTO_ACTIVATED`.
- **Ghi:** thời gian từ cắm nguồn đến **Trực tuyến**: ____ s.
- **Chụp:** `H1-serial.png` (Serial), `H1-web.png` (trang thiết bị).

> Chờ ít nhất 2 phút cho MQ-2 làm nóng trước khi làm H5.

### H2 – Telemetry và realtime

- **Thao tác:** để chạy 2 phút, cầm đầu DS18B20 cho nhiệt độ đổi.
- **Phải thấy:**
  - [ ] Dashboard đổi theo Serial mỗi chu kỳ, **không cần F5**.
  - [ ] Tab **Dữ liệu & biểu đồ** (chọn 1 giờ): có đường biểu đồ gas và nhiệt độ.
- **Ghi:** *Độ trễ TB (ms)* trong bảng thống kê: gas ____ ms, nhiệt độ ____ ms.
- **Chụp:** `H2-dashboard.png`, `H2-bieu-do.png`.

### H3 – Điều khiển quạt và còi

- **Thao tác:** tab **Điều khiển**: quạt **Bật** → **Tắt** → **Tự động**; còi **Bật** → **Tắt** → **Tự động**.
- **Phải thấy:**
  - [ ] Relay kêu tách, quạt quay / dừng; còi kêu / im.
  - [ ] [Serial] `Lệnh FAN_SET (…) -> SUCCESS: …` cho từng lệnh.
  - [ ] Web: thông báo *thành công sau … ms*; lịch sử lệnh có người gửi, **SUCCESS**.
- **Ghi:** độ trễ 3 lệnh quạt: ____ / ____ / ____ ms.
- **Chụp:** `H3-dieu-khien.mp4` (quay cả màn hình và quạt), `H3-lich-su-lenh.png`.

### H4 – Cấu hình từ xa

- **Thao tác:** tab **Cấu hình**: chu kỳ **3** s, ngưỡng **300** ppm → **Lưu và gửi xuống thiết bị**. Thử thêm ngưỡng
  **20** ppm.
- **Phải thấy:**
  - [ ] [Serial] `Áp dụng cấu hình v…: chu kỳ 3s, ngưỡng 300 ppm, tự bật quạt=1`; số đo dày hơn.
  - [ ] Web: **Đã áp dụng** đúng phiên bản.
  - [ ] Ngưỡng 20 ppm bị từ chối (cho phép 50–10000).
- **Sau ca này:** trả về chu kỳ **5** s, ngưỡng **400** ppm.
- **Chụp:** `H4-serial.png`, `H4-web.png`.

### H5 – Cảnh báo gas

- **An toàn:** nơi thoáng, không có lửa, xịt rất ít.
- **Thao tác:** xịt gas bật lửa cách MQ-2 khoảng 5 cm, 1–2 giây.
- **Phải thấy:**
  - [ ] Tại thiết bị: LED đỏ, còi, quạt tự bật; [Serial] `Gas … ppm > ngưỡng 400 -> TỰ BẬT QUẠT`.
  - [ ] Web: cảnh báo *Khí gas … ppm > 400 ppm (Nồng độ gas cao)*; nếu > 1000 ppm thêm cảnh báo CRITICAL.
  - [ ] Email cảnh báo đến hộp thư (1 email cho mỗi cảnh báo).
  - [ ] **Xác nhận** → khi gas giảm: cảnh báo hiện *đã trở lại bình thường* → **Đánh dấu đã xử lý** kèm ghi chú.
  - [ ] Hết báo động 60 s: [Serial] `Hết báo động 60 giây -> TỰ TẮT QUẠT`.
- **Ghi:** gas đỉnh ____ ppm; từ lúc xịt đến LED đỏ ____ s; email đến sau ____ s.
- **Chụp:** `H5-canh-bao-gas.mp4` (thiết bị + màn hình), `H5-email.png` (che địa chỉ email), `H5-canh-bao-web.png`.

### H10 – Quá nhiệt

- **Thao tác:** nhúng đầu DS18B20 vào nước khoảng 60 °C, giữ tới khi vượt 50 °C, rồi lấy ra.
- **Phải thấy:**
  - [ ] Trên 45 °C: LED đỏ, còi, quạt tự bật; [Serial] `Nhiệt độ … C > 45 C -> TỰ BẬT QUẠT`.
  - [ ] Trên 50 °C: web có cảnh báo *Nhiệt độ … °C > 50 °C (Nhiệt độ cao bất thường)*.
  - [ ] Lấy ra, dưới 42 °C: hết báo động tại chỗ.
- **Ghi:** nhiệt độ đỉnh ____ °C.
- **Chụp:** `H10-qua-nhiet.mp4`.

### H8 – Lỗi cảm biến

- **Thao tác:** rút dây DATA của DS18B20 (chân D4) khoảng 20 s, rồi cắm lại.
- **Phải thấy:**
  - [ ] Sau 3 lần đọc lỗi: [Serial] `LỖI cảm biến temp: …`; LED đỏ nháy chậm.
  - [ ] Web: trạng thái **Lỗi cảm biến** + cảnh báo *Lỗi cảm biến temp: …*.
  - [ ] Cắm lại: [Serial] `Cảm biến temp hoạt động lại`; web trở lại **Trực tuyến**, cảnh báo hết điều kiện.
- **Chụp:** `H8-serial.png`, `H8-web.png`.

### H7 – Mất kết nối broker, gửi bù

- **Thao tác:** PowerShell `dc stop mosquitto`, chờ 40 s, `dc start mosquitto`.
- **Phải thấy:**
  - [ ] [Serial] `Mất kết nối broker -> đo vẫn chạy, lưu đệm tối đa 120 bản`; LED xanh nháy chậm; dòng số đo vẫn in.
  - [ ] Báo động tại chỗ vẫn hoạt động nếu xịt gas lúc này (không bắt buộc).
  - [ ] Broker chạy lại: [Serial] `Đã kết nối broker`, rồi `Gửi bù … bản đo lưu lúc mất mạng (còn 0)`.
  - [ ] Biểu đồ không có khoảng trống trong 40 s đó (số đo gửi bù giữ đúng thời điểm đo).
  - [ ] Bình thường nếu web báo **Mất kết nối** (kèm cảnh báo, email) khi broker dừng quá 30 s: backend không nhận
    được bản tin nào nên watchdog đánh dấu offline; broker chạy lại thì tự **Trực tuyến**.
- **Ghi:** số bản gửi bù ____ (chu kỳ 5 s, 40 s thì khoảng 8 bản).
- **Chụp:** `H7-serial.png`, `H7-bieu-do.png`.

### H6 – Thiết bị mất kết nối (offline)

- **Thao tác:** rút USB của ESP32, bấm giờ; 30 s sau cắm lại.
- **Phải thấy:**
  - [ ] Web: khoảng 20–25 s sau chuyển **Mất kết nối**; có cảnh báo mất kết nối; email *Thiết bị mất kết nối*.
  - [ ] Cắm lại: tự **Trực tuyến**; cảnh báo hết điều kiện; email *Đã kết nối lại*.
- **Ghi:** từ lúc rút đến **Mất kết nối** ____ s; từ lúc cắm đến **Trực tuyến** ____ s.
- **Chụp:** `H6-offline.png`, `H6-online-lai.png`.

### H9 – Khóa và thu hồi

- **Thao tác:** trang thiết bị → **Tạm khóa** (nhập lý do); chờ 30 s; **Mở khóa**.
- **Phải thấy:**
  - [ ] Thiết bị bị ngắt ngay; [Serial] lặp lại `Broker TỪ CHỐI (mã 5): …`.
  - [ ] Web: **Tạm khóa**; gửi lệnh bị từ chối.
  - [ ] Mở khóa: thiết bị tự kết nối lại, [Serial] `Đã kết nối broker`.
- **Chụp:** `H9-serial.png`, `H9-web.png`.

## 2. Sau khi làm xong

- [ ] Cấu hình về chu kỳ 5 s, ngưỡng 400 ppm; quạt và còi **Tự động**; thiết bị không còn **Tạm khóa**.
- [ ] Xử lý hết cảnh báo còn mở.
- [ ] Chép ảnh, video vào `docs/images/tests/` (video lớn hơn khoảng 20 MB thì để ngoài repo, ghi đường dẫn).
- [ ] Điền `hardware-test-results.md`: Đạt / Không đạt, số liệu, tên file bằng chứng, ghi chú nếu khác mong đợi.
