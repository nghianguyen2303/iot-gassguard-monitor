# Kịch bản demo – GasGuard

Demo trực tiếp theo mục 8.4 của đề bài: **10 bước bắt buộc, khoảng 6–7 phút**. Mỗi bước ghi thao tác, điều cần chỉ cho
giảng viên và cách xử lý nếu có sự cố. Tên nút, tên tab và dòng Serial trong tài liệu này đúng như trong giao diện và
firmware hiện tại.

Ký hiệu: **[Laptop]** thao tác trên web hoặc PowerShell; **[ESP32]** thao tác trên phần cứng; **[Serial]** nhìn Serial
Monitor (115200 baud). Cột *Ai* là đề xuất, nhóm sửa theo thực tế.

## 1. Chuẩn bị

### 1.1. Tối hôm trước

| Việc | Cách làm |
|---|---|
| Cập nhật code | `git pull`, `app-up`, `dc ps`: 4 container `Up`, backend và frontend `healthy` |
| Tài khoản 3 vai trò | Đăng nhập `admin` → **Người dùng** → **+ Tạo người dùng**: một tài khoản OWNER (vd. `chunha`), một tài khoản VIEWER (vd. `nguoithan`). Mật khẩu chỉ nhóm biết, không ghi vào repo hay slide |
| Thiết bị thật của `chunha` | Thiết bị đã đăng ký **không đổi chủ được**, nên thiết bị thật dùng khi demo phải do `chunha` đăng ký: `chunha` → **Thiết bị** → **+ Đăng ký thiết bị**, mã `esp32-demo` → tab **Xác thực MQTT** → **Cấp thông tin xác thực** → sao chép vào `secrets.h` → nạp ESP32 → chạy thử tới khi **Hoạt động** + **Trực tuyến**. Tab **Chia sẻ** → chia sẻ quyền xem cho `nguoithan` |
| Email cảnh báo | `chunha` → **Tài khoản** → bật nhận email và xác nhận mã gửi về hộp thư nhóm sẽ mở khi demo |
| Thiết bị cho bước 2 | Chọn cách 1 (khuyên dùng) hoặc cách 2 ở bước 2 |
| Dọn cảnh báo cũ | **Cảnh báo**: xác nhận và xử lý các cảnh báo còn mở, để cảnh báo mới trong demo dễ thấy |
| Cấu hình về mặc định | Thiết bị thật: chu kỳ 5 s, ngưỡng 400 ppm, tự bật quạt; quạt và còi ở **Tự động** |
| Đạo cụ | Bật lửa gas (chỉ xịt, không đánh lửa), dây USB dự phòng, ổ cắm |
| Video dự phòng | Quay lại toàn bộ 10 bước theo kịch bản này (mục 4) |

### 1.2. Trước giờ demo 15 phút (tại phòng)

1. **Mạng:** bật hotspot điện thoại ở **2,4 GHz**, có Internet. ESP32 cần Internet để lấy giờ NTP; backend cần
   Internet để gửi email. Laptop vào hotspot, đặt mạng là **Private**.
2. **IP laptop:** `ipconfig` → ghi IPv4 của card WiFi (vd. `172.20.10.2`).
3. **Chứng chỉ:** nếu IP này chưa có trong chứng chỉ, chạy `gen-certs <IP nhà> <IP hotspot>` rồi
   **`dc restart mosquitto frontend`**. Bước khởi động lại là bắt buộc: `app-up` không nạp lại chứng chỉ.
   Nên làm trước ở nhà với IP hotspot của chính điện thoại sẽ dùng.
4. **ESP32:** nếu IP khác lần nạp trước, sửa `MQTT_HOST` (và WiFi nếu đổi) trong `secrets.h`, nạp lại. Không phải thay
   `ca_cert.h`.
5. **Cấp nguồn ESP32 sớm** (trừ khi chọn cách 2 ở bước 2): MQ-2 cần làm nóng 60 giây mới có số đo gas, nên làm nóng
   thêm vài phút cho ổn định.
   [Serial] phải thấy `Đã đồng bộ giờ NTP`, `Kết nối MQTT (TLS) <IP>:<cổng> ...`, `Đã kết nối broker`.
6. **Trình duyệt:** mở `https://<IP>:8443` trong **3 cửa sổ tách biệt** (Chrome thường, Chrome ẩn danh, Edge), vì
   phiên đăng nhập lưu theo trình duyệt nên cùng một cửa sổ không giữ được 2 vai trò. Không mở bằng `http://` ở cổng
   8443 (lỗi 400).
7. **PowerShell:** mở sẵn ở thư mục repo, đã cài thư viện của simulator (`tests\simulator\requirements.txt`), cần cho bước 2 cách 1.
8. **Kiểm tra cuối:** web hiện `esp32-demo` **Trực tuyến**, số đo gas và nhiệt độ nhảy mỗi 5 s (nếu chọn cách 2 ở bước 2 thì làm theo ghi chú ở đó).

## 2. Mười bước demo

| Bước | Ai | Thời gian |
|---|---|---|
| 1. Đăng nhập 3 vai trò | FE | 0:40 |
| 2. Đăng ký / kích hoạt thiết bị | BE | 1:00 |
| 3. Thiết bị gửi dữ liệu | BE | 0:30 |
| 4. Dashboard realtime | FE | 0:30 |
| 5. Lệnh điều khiển + phản hồi | FE | 0:40 |
| 6. Đổi cấu hình | FE | 0:30 |
| 7. Mất kết nối → offline | BE | 0:50 |
| 8. Kích hoạt cảnh báo | BE | 0:50 |
| 9. Thao tác không được cấp quyền | FE | 0:30 |
| 10. Khóa thiết bị, chứng minh không kết nối được | BE | 0:40 |

> **Nên làm bước 8 trước bước 7.** Rút nguồn ở bước 7 làm ESP32 khởi động lại và MQ-2 phải làm nóng thêm 60 giây mới
> đo được gas. Nếu giảng viên muốn đúng thứ tự, làm bước 7 trước rồi dùng phần chờ 60 giây để trả lời câu hỏi.

### Bước 1 – Đăng nhập bằng các vai trò khác nhau

- **[Laptop]** Đăng nhập lần lượt 3 cửa sổ: `admin`, `chunha` (OWNER), `nguoithan` (VIEWER).
- **Chỉ cho giảng viên:**
  - Menu của `admin` có **Người dùng**, **Audit log**, **Hệ thống**; hai vai trò kia không có.
  - `nguoithan` chỉ thấy thiết bị được chia sẻ, không có nút điều khiển.
  - Có thể mở **Người dùng** của `admin` để thấy 3 vai trò và nút **Khóa**.
- **Câu nói:** "Vai trò lấy từ CSDL ở mỗi request, không tin vào token; đổi vai trò hay khóa tài khoản thì mọi token cũ
  hết hiệu lực ngay."

### Bước 2 – Đăng ký hoặc kích hoạt một thiết bị

**Cách 1, khuyên dùng: đăng ký trực tiếp một thiết bị mới + cho xem thiết bị thật đã qua đủ vòng đời.**
ESP32 thật (`esp32-demo`) đã chạy và làm nóng sẵn, nên các bước sau không phải chờ.
- **[Laptop]** `chunha` → **Thiết bị** → **+ Đăng ký thiết bị**, mã `sim-demo` → trạng thái **Đã đăng ký**.
- Tab **Xác thực MQTT** → **Cấp thông tin xác thực** → trạng thái **Đã cấp quyền**; username = clientId = mã thiết bị,
  mật khẩu ngẫu nhiên chỉ hiện **một lần** → **Sao chép**.
- **[Laptop]** PowerShell (simulator nói đúng giao thức của firmware, chạy trên chính máy chủ nên dùng cổng nội bộ):
  `python tests\simulator\simulator.py --host 127.0.0.1 --device sim-demo --password <mật khẩu vừa cấp>`
- **[Laptop]** Vài giây sau `sim-demo` tự chuyển **Hoạt động** + **Trực tuyến**: kích hoạt khi nhận bản tin đầu tiên.
- **[Laptop]** Mở `esp32-demo` → tab **Lịch sử hoạt động**: thiết bị thật đã đi qua đúng các bước đó
  (`DEVICE_REGISTER`, `DEVICE_PROVISION`, `DEVICE_AUTO_ACTIVATED`).
- Nói rõ: thiết bị mô phỏng chỉ để minh họa đăng ký nhanh; mọi bước còn lại dùng ESP32 thật. `sim-demo` dùng tiếp ở
  bước 10 (ngừng sử dụng).

**Cách 2: kích hoạt chính ESP32 thật trước mặt giảng viên.** Tối hôm trước làm như mục 1.1 nhưng **không cho ESP32
chạy** sau khi nạp (nếu đã chạy thì thiết bị đã kích hoạt, phải đăng ký mã mới). Kiểm tra mạng và chứng chỉ trước
bằng một mã thiết bị khác.
- **[Laptop]** `esp32-demo` đang **Đã cấp quyền**; tab **Xác thực MQTT** cho thấy username = clientId = mã thiết bị.
- **[ESP32]** Cắm USB. [Serial] `Đã đồng bộ giờ NTP` → `Đã kết nối broker` (khoảng 10–20 s).
- **[Laptop]** Thiết bị tự chuyển **Hoạt động** + **Trực tuyến**, có phiên bản firmware 1.0.0.
- Lưu ý: MQ-2 cần 60 giây làm nóng, nên ở bước 3–4 trong phút đầu chỉ có nhiệt độ; tới bước 8 gas đã đo được.

### Bước 3 – Thiết bị gửi dữ liệu cảm biến

- **[Serial]** Mỗi 5 s một dòng `gas=… ppm  temp=… C  quạt=…  còi=…`.
- **[Laptop]** Tab **Dữ liệu & biểu đồ** → bảng số đo: thời điểm đo, `seq` tăng dần, đơn vị `ppm` / `C`, cột
  **Độ trễ (ms)**.
- **Câu nói:** "Bản tin JSON qua MQTT/TLS, QoS1, có `seq` và `ts` UTC lấy từ NTP. Backend kiểm tra hợp lệ rồi mới lưu,
  chống trùng bằng khóa (thiết bị, chỉ số, thời điểm)."

### Bước 4 – Dashboard hiển thị dữ liệu thời gian thực

- **[Laptop]** Mở **Dashboard** cạnh Serial Monitor; thổi nhẹ hoặc cầm DS18B20 để nhiệt độ đổi. Số trên web đổi
  cùng nhịp Serial, **không cần F5**.
- Tab **Dữ liệu & biểu đồ** → chọn **1 giờ**: biểu đồ; bảng thống kê có **Độ trễ TB (ms)** (thường vài chục ms).
- **Câu nói:** "Đẩy qua WebSocket (STOMP), chỉ gửi cho người có quyền xem thiết bị đó."

### Bước 5 – Gửi lệnh điều khiển và nhận phản hồi

- **[Laptop]** `chunha` → tab **Điều khiển** → quạt **Bật**.
- **[ESP32]** Relay kêu tách, quạt quay. **[Serial]** `Lệnh FAN_SET (…) -> SUCCESS: Quạt BẬT (giữ bật đến khi đổi lại)`.
- **[Laptop]** Thông báo *Quạt: thành công sau … ms*; lịch sử lệnh có người gửi, trạng thái **SUCCESS**, độ trễ.
- Bấm **Tự động** để trả quạt về chế độ tự động. Có thể làm tương tự với còi.
- **Câu nói:** "Mỗi lệnh có `commandId`; thiết bị trả ACK kèm trạng thái thật. Không có ACK sau 10 s thì lệnh thành
  TIMEOUT."

### Bước 6 – Thay đổi cấu hình thiết bị

- **[Laptop]** Tab **Cấu hình**: chu kỳ **3** giây, ngưỡng **300** ppm → **Lưu và gửi xuống thiết bị**.
- **[Serial]** `Áp dụng cấu hình v…: chu kỳ 3s, ngưỡng 300 ppm, tự bật quạt=1`; dòng số đo dày hơn.
- **[Laptop]** Trạng thái cấu hình **Đã áp dụng** đúng phiên bản.
- Có thể thử ngưỡng 20 ppm → form báo lỗi (cho phép 50–10000): kiểm tra ở cả backend và firmware.
- Để nguyên ngưỡng 300 ppm cho bước 8 (dễ kích hoạt hơn); trả về 5 s / 400 ppm sau demo.

### Bước 8 – Kích hoạt một cảnh báo (nên làm trước bước 7)

- **[ESP32]** Xịt một chút gas bật lửa (không đánh lửa) cách MQ-2 khoảng 5 cm, nơi thoáng.
- **[ESP32]** LED đỏ sáng, còi kêu, quạt tự bật, ngay tại thiết bị, không chờ mạng. [Serial]
  `Gas … ppm > ngưỡng 300 -> TỰ BẬT QUẠT`.
- **[Laptop]** Cảnh báo hiện ngay: *Khí gas … ppm > 400 ppm (Nồng độ gas cao)*, nếu gas > 1000 thêm cảnh báo
  CRITICAL. Mở hộp thư: email cảnh báo (1 email cho mỗi cảnh báo).
- **[Laptop]** `chunha` → **Xác nhận** → sau khi gas giảm, **Đánh dấu đã xử lý** kèm ghi chú.
- Cho thấy `nguoithan` (VIEWER) thấy cảnh báo nhưng không có nút xác nhận.

### Bước 7 – Ngắt kết nối để hệ thống phát hiện thiết bị offline

- **[ESP32]** Rút USB (giống mất điện đột ngột). Bấm giờ.
- **[Laptop]** Khoảng 20–25 giây sau, thiết bị chuyển **Mất kết nối**, có cảnh báo mất kết nối và email
  *[GasGuard] Thiết bị mất kết nối – …*.
- **Câu nói:** "Rút điện thì kết nối TCP không đóng kịp; broker đợi 1,5 × keepalive (15 s) rồi phát bản tin LWT. Nếu
  thiết bị treo mà không phát LWT, watchdog của backend đánh dấu offline sau 30 s không có bản tin."
- **[ESP32]** Cắm lại → tự kết nối lại → **Trực tuyến**, cảnh báo offline được đánh dấu hết điều kiện, email
  *Đã kết nối lại*.

### Bước 9 – Thử một thao tác không được cấp quyền

- **[Laptop]** Cửa sổ `nguoithan` (VIEWER): gõ thẳng địa chỉ `https://<IP>:8443/users` (trang quản lý người dùng
  không có trên menu của VIEWER).
- Trang báo lỗi từ **backend** (HTTP 403), không có danh sách người dùng: giao diện ẩn menu chỉ để tiện dùng, quyền
  thật kiểm tra ở backend.
- **[Laptop]** Cửa sổ `admin` → **Audit log** → lọc kết quả **DENIED**: có dòng `ACCESS_DENIED` của `nguoithan` kèm
  IP và thời gian.
- Có thể nói thêm: ca kiểm thử tự động S1, S2, S3 đã chứng minh VIEWER gửi lệnh, đổi cấu hình, xem thiết bị người
  khác, dùng token giả đều bị từ chối (`tests/reports/e2e-latest.md`).

### Bước 10 – Khóa hoặc ngừng sử dụng thiết bị, chứng minh không còn kết nối được

**Khóa thiết bị thật (có thể mở lại):**
- **[Laptop]** `chunha` → thiết bị → **Tạm khóa**, nhập lý do → xác nhận.
- **[Serial]** Thiết bị bị ngắt ngay; khi thử lại: `Broker TỪ CHỐI (mã 5): sai mật khẩu, hoặc thiết bị bị tạm ngưng /
  thu hồi / đổi mật khẩu`, lặp lại.
- **[Laptop]** Trạng thái **Tạm khóa** + **Mất kết nối**; bấm gửi lệnh bị backend từ chối (thiết bị không ở trạng thái nhận lệnh).
- Sau khi giảng viên đã thấy: **Mở khóa** → thiết bị tự kết nối lại.

**Ngừng sử dụng vĩnh viễn** (chỉ làm với `sim-demo` nếu đã dùng cách 1 ở bước 2, không làm với ESP32 thật vì không đảo
ngược được):
- **[Laptop]** `sim-demo` → **Ngừng sử dụng**, nhập lý do.
- Simulator đang chạy bị ngắt; dừng (Ctrl+C) rồi chạy lại lệnh ở bước 2 → `Broker TỪ CHỐI kết nối: …`. Lịch sử dữ
  liệu của `sim-demo` vẫn xem được.

## 3. Sự cố thường gặp

| Hiện tượng | Nguyên nhân thường gặp | Xử lý nhanh |
|---|---|---|
| [Serial] `Lỗi TLS: …` | IP hiện tại của laptop không có trong chứng chỉ, hoặc chưa khởi động lại sau `gen-certs` | `gen-certs <các IP>` → `dc restart mosquitto frontend` |
| [Serial] `Không kết nối được broker … cổng …` | Sai `MQTT_HOST`, tường lửa chưa mở cổng MQTT/TLS, mạng là Public | Kiểm tra IP, đặt mạng Private, mở cổng (8883 hoặc `MQTT_TLS_PORT`) |
| [Serial] `Chưa có giờ NTP sau 30 giây` | Hotspot không ra Internet hoặc chặn UDP 123 | Bật dữ liệu di động cho hotspot |
| [Serial] `Broker TỪ CHỐI (mã 5)` khi không chủ đích | Thiết bị đang bị tạm khóa, hoặc mật khẩu đã đổi | Mở khóa trên web; hoặc cấp lại và nạp lại `secrets.h` |
| Gas không có số đo | MQ-2 đang làm nóng 60 s sau khi cấp nguồn | Chờ; nói về luồng dữ liệu trong lúc chờ |
| Web báo lỗi chứng chỉ | Máy trình chiếu chưa cài CA của nhóm | Dùng laptop đã cài CA |
| Trang trắng / lỗi 400 | Mở `http://…:8443` | Gõ đủ `https://` |
| Không thấy email | Mạng chặn cổng 587, hoặc email vào Spam | Hotspot điện thoại; xem thư mục Spam |
| Mọi thứ hỏng | | Chuyển sang video dự phòng, vẫn trả lời vấn đáp trên code |

## 4. Video dự phòng

Quay màn hình laptop (web + Serial Monitor), kèm điện thoại quay ESP32 (LED, còi, quạt) khi làm đủ 10 bước. Không
để lộ `.env`, `secrets.h`, mật khẩu trong video. Lưu ngoài repo (file lớn), ghi đường dẫn vào báo cáo. Đề bài: video
không thay thế demo trực tiếp.

## 5. Sau demo

- Trả cấu hình về chu kỳ 5 s, ngưỡng 400 ppm.
- Xử lý các cảnh báo còn mở.
- Nếu thiết bị thật còn ở trạng thái **Tạm khóa**, bấm **Mở khóa**.
