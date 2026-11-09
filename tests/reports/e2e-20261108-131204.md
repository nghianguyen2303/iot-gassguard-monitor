# Báo cáo kiểm thử chức năng (7.1) và bảo mật (4.5) – GasGuard

- Thời điểm chạy: **13:12:04 08/11/2026** (giờ Việt Nam)
- Thời gian chạy: 39 s
- API: `http://127.0.0.1:8080`
- WebSocket: `ws://127.0.0.1:8080/ws`
- MQTT: `127.0.0.1:1883`
- MQTT/TLS: `127.0.0.1:8884`
- Web HTTPS: `https://127.0.0.1:8443`
- Máy chạy test: `Windows 10 / Python 3.11.7`
- Kết quả: **18 đạt / 0 không đạt / 0 bỏ qua** trên 18 ca

| Mã | Ca kiểm thử | Yêu cầu | Kết quả | Thời gian |
|---|---|---|---|---|
| F1 | Đăng nhập và phân quyền theo vai trò | 7.1 · 4.4a · 8.4-1 | ✅ Đạt | 1.2 s |
| F2 | Đăng ký, cấp xác thực và kích hoạt thiết bị | 7.1 · 4.4b · 8.4-2 | ✅ Đạt | 0.7 s |
| F3 | Thiết bị gửi telemetry, backend kiểm tra, lưu và truy vấn | 7.1 · 4.4c · 8.4-3 | ✅ Đạt | 0.7 s |
| F4 | Dashboard cập nhật dữ liệu thời gian thực (WebSocket) | 7.1 · 4.4d · 8.4-4 | ✅ Đạt | 0.4 s |
| F5 | Gửi lệnh điều khiển và nhận xác nhận (ACK) | 7.1 · 4.4e · 8.4-5 | ✅ Đạt | 11.8 s |
| F6 | Thay đổi cấu hình thiết bị từ xa | 4.4b · 8.4-6 | ✅ Đạt | 0.3 s |
| F7 | Phát hiện thiết bị offline và trở lại online | 7.1 · 4.4d · 8.4-7 | ✅ Đạt | 1.0 s |
| F8 | Sinh cảnh báo theo luật và xử lý cảnh báo | 7.1 · 4.4d · 8.4-8 | ✅ Đạt | 0.5 s |
| F9 | Khóa (tạm ngưng) và ngừng sử dụng thiết bị | 7.1 · 4.4b · 4.5 · 8.4-10 | ✅ Đạt | 1.4 s |
| S1 | Người dùng không truy cập được thiết bị của người khác | 4.5 · 8.4-9 | ✅ Đạt | 3.5 s |
| S2 | Người được chia sẻ chỉ được xem, không được điều khiển | 4.4a · 4.5 | ✅ Đạt | 0.2 s |
| S3 | API từ chối token thiếu, sai, giả mạo, hết hạn hoặc đã thu hồi | 4.5 | ✅ Đạt | 0.6 s |
| S4 | Broker từ chối kết nối sai và publish/subscribe sai topic | 4.5 · 4.3 | ✅ Đạt | 1.6 s |
| S5 | Hệ thống kiểm tra và từ chối dữ liệu không hợp lệ | 4.5 · 4.4c | ✅ Đạt | 2.1 s |
| S6 | Chống dò mật khẩu: khóa tạm sau 5 lần sai | 4.5 · 4.4a | ✅ Đạt | 1.6 s |
| S7 | Thu hồi thông tin xác thực: mật khẩu cũ của thiết bị hết hiệu lực | 4.5 · 4.4b | ✅ Đạt | 1.1 s |
| S8 | Audit log thao tác quan trọng và mật khẩu được băm | 4.5 | ✅ Đạt | 0.5 s |
| S9 | Kết nối mã hóa TLS: MQTT 8883 và web HTTPS | 4.5 | ✅ Đạt | 7.0 s |

## Chi tiết từng ca

### F1 – Đăng nhập và phân quyền theo vai trò
*Yêu cầu: 7.1 · 4.4a · 8.4-1*  
*Kết quả: PASS*

- ✔ e2e_owner_1108131204 đăng nhập được, /me trả vai trò OWNER
- ✔ e2e_viewer_1108131204 đăng nhập được, /me trả vai trò VIEWER
- ✔ admin đăng nhập được, vai trò ADMIN
- ✔ Sai mật khẩu -> HTTP 401 (mong đợi 401)
- ✔ Quản lý người dùng: VIEWER 403, OWNER 403, ADMIN 200 (mong đợi 403/403/200)
- ✔ VIEWER đăng ký thiết bị -> HTTP 403 (mong đợi 403)
- ✔ Tự đăng ký tài khoản -> HTTP 201, vai trò mặc định VIEWER
- ✔ Sau đăng xuất, token cũ -> HTTP 401 (mong đợi 401)

### F2 – Đăng ký, cấp xác thực và kích hoạt thiết bị
*Yêu cầu: 7.1 · 4.4b · 8.4-2*  
*Kết quả: PASS*

- ✔ Đăng ký e2e-1108131204 -> HTTP 201, trạng thái REGISTERED
- ✔ Đăng ký trùng mã -> HTTP 409 (mong đợi 409)
- ✔ Cấp thông tin xác thực riêng -> username = clientId = e2e-1108131204, mật khẩu 24 ký tự
- ✔ Trạng thái PROVISIONED sau khi cấp
- ✔ Xem lại thông tin xác thực KHÔNG trả mật khẩu (chỉ hiện 1 lần)
- ✔ Thiết bị kết nối broker bằng thông tin riêng -> CONNACK 0
- ✔ Bản tin đầu tiên -> tự kích hoạt ACTIVE, kết nối ONLINE
- ✔ Thiết bị thứ 2 (e2e-1108131204-b, chủ khác) kết nối bằng mật khẩu riêng của nó

### F3 – Thiết bị gửi telemetry, backend kiểm tra, lưu và truy vấn
*Yêu cầu: 7.1 · 4.4c · 8.4-3*  
*Kết quả: PASS*

- ✔ Lưu đủ 10 bản tin x 2 chỉ số = 22 điểm
- ✔ Giá trị gas lưu đúng như thiết bị gửi
- ✔ Đơn vị chuẩn hóa: {'gas': 'ppm', 'temp': 'C'}
- ✔ Mỗi điểm có seq (phát hiện mất bản tin)
- ✔ Giá trị hiện tại (latest) gas = 64.0
- ✔ Thống kê: count 11, min 40.0, max 64.0, avg 57.73
- ✔ Dữ liệu biểu đồ (series) -> HTTP 200
- ✔ Xuất CSV -> HTTP 200, 23 dòng

### F4 – Dashboard cập nhật dữ liệu thời gian thực (WebSocket)
*Yêu cầu: 7.1 · 4.4d · 8.4-4*  
*Kết quả: PASS*

- ✔ Dashboard (STOMP/WebSocket) kết nối bằng JWT
- ✔ Bản tin vừa gửi xuất hiện trên kênh realtime của dashboard
- ✔ Độ trễ thiết bị publish -> dashboard nhận: 14 ms
- ✔ API dashboard: e2e-1108131204 trực tuyến, gas hiện tại 77.7

### F5 – Gửi lệnh điều khiển và nhận xác nhận (ACK)
*Yêu cầu: 7.1 · 4.4e · 8.4-5*  
*Kết quả: PASS*

- ✔ FAN_SET bật -> HTTP 201, trạng thái SUCCESS, độ trễ 65 ms
- ✔ Thiết bị nhận đúng lệnh (commandId khớp)
- ✔ ACK kèm trạng thái mới: {'fan': True, 'buzzer': False, 'fanMode': 'MANUAL', 'buzzerMode': 'AUTO'}
- ✔ Backend cập nhật trạng thái quạt = BẬT
- ✔ PING -> SUCCESS (56 ms)
- ✔ Lịch sử lệnh lưu cả 2 lệnh (người gửi 9)
- ✔ Lệnh không hỗ trợ -> HTTP 400 (mong đợi 400)
- ✔ Thiết bị không phản hồi -> lệnh chuyển TIMEOUT sau 10 s

### F6 – Thay đổi cấu hình thiết bị từ xa
*Yêu cầu: 4.4b · 8.4-6*  
*Kết quả: PASS*

- ✔ Lưu cấu hình v1 -> HTTP 200
- ✔ Thiết bị nhận cấu hình (bản tin retained) với ngưỡng 500
- ✔ Thiết bị xác nhận APPLIED, phiên bản áp dụng 1
- ✔ Cấu hình sai (chu kỳ 0 s) -> HTTP 400 (mong đợi 400)

### F7 – Phát hiện thiết bị offline và trở lại online
*Yêu cầu: 7.1 · 4.4d · 8.4-7*  
*Kết quả: PASS*

- ✔ Ngắt đột ngột (mất điện) -> OFFLINE sau 0.0 s (LWT của broker)
- ✔ Sinh cảnh báo mất kết nối (OFFLINE)
- ✔ Gửi lệnh khi offline -> HTTP 409 DEVICE_OFFLINE (mong đợi 409)
- ✔ Thiết bị kết nối lại
- ✔ Trạng thái trở lại ONLINE

### F8 – Sinh cảnh báo theo luật và xử lý cảnh báo
*Yêu cầu: 7.1 · 4.4d · 8.4-8*  
*Kết quả: PASS*

- ✔ Gas 1200 ppm -> cảnh báo ['CRITICAL', 'WARNING'] theo luật mặc định (>400, >1000)
- ✔ Nội dung: Khí gas 1200 ppm > 1000 ppm (Rò rỉ gas nguy hiểm)
- ✔ Dashboard nhận thông báo cảnh báo mới qua WebSocket
- ✔ Người không có quyền xác nhận cảnh báo -> HTTP 403
- ✔ Chủ thiết bị xác nhận -> ACKNOWLEDGED bởi e2e_owner_1108131204
- ✔ Gas trở lại bình thường -> cảnh báo được đánh dấu đã hết điều kiện
- ✔ Xử lý xong -> RESOLVED, ghi chú 'Đã thông gió'

### F9 – Khóa (tạm ngưng) và ngừng sử dụng thiết bị
*Yêu cầu: 7.1 · 4.4b · 4.5 · 8.4-10*  
*Kết quả: PASS*

- ✔ Tạm khóa không ghi lý do -> HTTP 400 (bắt buộc lý do)
- ✔ Tạm khóa -> SUSPENDED
- ✔ Broker ngắt ngay phiên MQTT đang mở (mã 0)
- ✔ Kết nối lại khi đang tạm khóa -> CONNACK 135 (bị từ chối)
- ✔ Gửi lệnh tới thiết bị bị khóa -> HTTP 409
- ✔ Mở khóa (RESUME) -> ACTIVE, thiết bị kết nối lại được
- ✔ Ngừng sử dụng -> DECOMMISSIONED
- ✔ Thiết bị đã ngừng sử dụng kết nối -> CONNACK 135 (bị từ chối vĩnh viễn)
- ✔ Không còn trong danh sách thiết bị đang dùng
- ✔ Dữ liệu lịch sử vẫn được giữ để tra cứu
- ✔ Không thể kích hoạt lại thiết bị đã ngừng sử dụng -> HTTP 409
- ✔ Audit log ghi 3 thao tác vòng đời (khóa / mở khóa / ngừng sử dụng) kèm lý do

### S1 – Người dùng không truy cập được thiết bị của người khác
*Yêu cầu: 4.5 · 8.4-9*  
*Kết quả: PASS*

- ✔ GET /api/devices/{thiết bị người khác} -> HTTP 403
- ✔ GET /api/devices/{thiết bị người khác}/telemetry -> HTTP 403
- ✔ GET /api/devices/{thiết bị người khác}/config -> HTTP 403
- ✔ POST /api/devices/{thiết bị người khác}/commands -> HTTP 403
- ✔ PUT /api/devices/{thiết bị người khác}/config -> HTTP 403
- ✔ POST /api/devices/{thiết bị người khác}/lifecycle -> HTTP 403
- ✔ Danh sách thiết bị không lộ thiết bị của người khác
- ✔ Đăng ký kênh realtime của thiết bị người khác -> không nhận được dữ liệu ['ERROR\nmessage:Không có quyền xem thiết bị e2e-1108131204-b\ncontent-length:0\n\n']
- ✔ Audit log ghi 8 lần bị từ chối của e2e_owner_1108131204

### S2 – Người được chia sẻ chỉ được xem, không được điều khiển
*Yêu cầu: 4.4a · 4.5*  
*Kết quả: PASS*

- ✔ Chủ thiết bị chia sẻ cho e2e_viewer_1108131204 -> HTTP 201
- ✔ Người xem đọc được thiết bị (canManage=False)
- ✔ Người xem đọc được dữ liệu
- ✔ Người xem POST /commands -> HTTP 403 (mong đợi 403, kiểm tra ở backend)
- ✔ Người xem PUT /config -> HTTP 403 (mong đợi 403, kiểm tra ở backend)
- ✔ Người xem POST /credentials/rotate -> HTTP 403 (mong đợi 403, kiểm tra ở backend)
- ✔ Người xem POST /lifecycle -> HTTP 403 (mong đợi 403, kiểm tra ở backend)

### S3 – API từ chối token thiếu, sai, giả mạo, hết hạn hoặc đã thu hồi
*Yêu cầu: 4.5*  
*Kết quả: PASS*

- ✔ Không có token -> HTTP 401
- ✔ Token rác -> HTTP 401
- ✔ Token bị sửa chữ ký -> HTTP 401
- ✔ Tự sửa vai trò trong token thành ADMIN -> HTTP 401
- ✔ Token đúng chữ ký nhưng đã hết hạn -> HTTP 401
- ✔ Token sau khi đăng xuất bị thu hồi -> 401
- ✔ WebSocket từ chối CONNECT với token sai

### S4 – Broker từ chối kết nối sai và publish/subscribe sai topic
*Yêu cầu: 4.5 · 4.3*  
*Kết quả: PASS*

- ✔ Thiết bị A publish vào topic của thiết bị B -> PUBACK 135 (Not authorized)
- ✔ Dữ liệu giả không vào được hệ thống (2 -> 2 điểm)
- ✔ Thiết bị tự publish lệnh vào topic cmd -> PUBACK 135
- ✔ Subscribe lệnh của thiết bị B -> SUBACK [135]
- ✔ Subscribe wildcard toàn hệ thống -> SUBACK [135]
- ✔ Kết nối sai mật khẩu -> CONNACK 135
- ✔ Dùng mật khẩu của A để đăng nhập tên B -> CONNACK 135
- ✔ Đúng mật khẩu nhưng clientId khác mã thiết bị -> CONNACK 135
- ✔ Kết nối ẩn danh -> CONNACK 135
- ✔ Thiết bị hợp lệ vẫn hoạt động bình thường

### S5 – Hệ thống kiểm tra và từ chối dữ liệu không hợp lệ
*Yêu cầu: 4.5 · 4.4c*  
*Kết quả: PASS*

- ✔ Bản tin lỗi MALFORMED_JSON bị từ chối (bộ đếm +1)
- ✔ Bản tin lỗi MISSING_TS bị từ chối (bộ đếm +1)
- ✔ Bản tin lỗi TS_IN_FUTURE bị từ chối (bộ đếm +1)
- ✔ Bản tin lỗi TS_TOO_OLD bị từ chối (bộ đếm +1)
- ✔ Bản tin lỗi OUT_OF_RANGE bị từ chối (bộ đếm +1)
- ✔ Bản tin lỗi WRONG_UNIT bị từ chối (bộ đếm +1)
- ✔ Bản tin lỗi UNKNOWN_FIELD bị từ chối (bộ đếm +1)
- ✔ Bản tin lỗi DEVICE_ID_MISMATCH bị từ chối (bộ đếm +1)
- ✔ Bản tin lỗi PAYLOAD_TOO_LARGE bị từ chối (bộ đếm +1)
- ✔ Không bản tin lỗi nào được lưu vào CSDL
- ✔ REST: dữ liệu sai -> HTTP 400, lỗi theo trường ['deviceCode', 'name']

### S6 – Chống dò mật khẩu: khóa tạm sau 5 lần sai
*Yêu cầu: 4.5 · 4.4a*  
*Kết quả: PASS*

- ✔ Sai mật khẩu 5 lần liên tiếp -> [401, 401, 401, 401, 423] (lần thứ 5 kích hoạt khóa tạm 15 phút: 423)
- ✔ Lần 6 dù ĐÚNG mật khẩu vẫn bị khóa tạm -> HTTP 423
- ✔ Admin mở khóa -> đăng nhập lại được (200)

### S7 – Thu hồi thông tin xác thực: mật khẩu cũ của thiết bị hết hiệu lực
*Yêu cầu: 4.5 · 4.4b*  
*Kết quả: PASS*

- ✔ Đổi mật khẩu thiết bị (rotate) -> cấp mật khẩu mới
- ✔ Phiên MQTT dùng mật khẩu cũ bị ngắt
- ✔ Mật khẩu cũ bị từ chối -> thiết bị/kẻ lấy cắp mật khẩu không gửi dữ liệu được
- ✔ Mật khẩu mới hoạt động

### S8 – Audit log thao tác quan trọng và mật khẩu được băm
*Yêu cầu: 4.5*  
*Kết quả: PASS*

- ✔ Audit có 'DEVICE_REGISTER' cho e2e-1108131204
- ✔ Audit có 'DEVICE_PROVISION' cho e2e-1108131204
- ✔ Audit có 'COMMAND_SEND' cho e2e-1108131204
- ✔ Audit có 'DEVICE_CONFIG_UPDATE' cho e2e-1108131204
- ✔ Audit có 'DEVICE_CREDENTIAL_ROTATE' cho e2e-1108131204
- ✔ Audit có 'DEVICE_SHARE_ADD' cho e2e-1108131204
- ✔ Các hành động đã ghi: COMMAND_SEND, DATA_EXPORT, DEVICE_AUTO_ACTIVATED, DEVICE_CONFIG_UPDATE, DEVICE_CREDENTIAL_ROTATE, DEVICE_PROVISION, DEVICE_REGISTER, DEVICE_SHARE_ADD
- ✔ API người dùng không trả mật khẩu/băm
- ✔ CSDL lưu BCrypt: $2a$12$… (không có mật khẩu thuần)

### S9 – Kết nối mã hóa TLS: MQTT 8883 và web HTTPS
*Yêu cầu: 4.5*  
*Kết quả: PASS*

- ✔ Thiết bị kết nối MQTT qua TLS cổng 8884, kiểm tra chứng chỉ broker bằng CA của nhóm -> CONNACK 0, TLSv1.3, TLS_AES_256_GCM_SHA384
- ✔ Dữ liệu gửi qua TLS được kiểm tra và lưu như bình thường (2 điểm, thiết bị ACTIVE)
- ✔ MQTT không mã hóa vào cổng 8884 -> không có CONNACK (broker đóng kết nối)
- ✔ Chứng chỉ broker không do CA tin cậy ký -> client từ chối (CERTIFICATE_VERIFY_FAILED)
- ✔ Từ địa chỉ LAN 192.168.5.176: cổng 1883 (không mã hóa) đóng, cổng 8884 (TLS) mở
- ✔ Web HTTP -> HTTP 301, chuyển hướng tới https://127.0.0.1:8443/devices
- ✔ Gọi API đăng nhập qua HTTP -> HTTP 301 (không xử lý qua kết nối không mã hóa)
- ✔ Trang web qua HTTPS với chứng chỉ của nhóm -> HTTP 200
- ✔ Đăng nhập qua HTTPS (cùng origin) -> HTTP 200
- ✔ Trình duyệt/client chưa tin CA của nhóm -> cảnh báo chứng chỉ (CERTIFICATE_VERIFY_FAILED)

