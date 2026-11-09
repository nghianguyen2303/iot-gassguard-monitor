# Kế hoạch kiểm thử – GasGuard

Tài liệu này đáp ứng mục **7. Kiểm thử và đánh giá** của đề bài: kế hoạch kiểm thử, phương pháp đo và
nơi lưu bằng chứng. Kết quả thực tế nằm ở [`docs/test-report.md`](test-report.md) (tự sinh từ lần chạy gần nhất).

## 1. Mục tiêu và phạm vi

- Chứng minh các quy trình chức năng bắt buộc (mục 7.1) hoạt động đầu-cuối: thiết bị → broker → backend → CSDL → giao diện.
- Chứng minh các biện pháp bảo mật (mục 4.5), vượt mức tối thiểu 2 kiểm thử bảo mật mà đề bài yêu cầu.
- Đo các chỉ tiêu phi chức năng (mục 7.2), mỗi chỉ tiêu có **phương pháp đo, dữ liệu đo và nhận xét**.
- Kiểm thử trên **thiết bị vật lý** ESP32 + MQ-2 + DS18B20 + relay/quạt + LED/còi (mục 8.4, điều kiện mục 9).

Ngoài phạm vi: kiểm thử tải trên Internet công cộng, chứng nhận an toàn điện. Thiết bị chạy 5V USB, không đấu điện lưới (mục 10).

## 2. Môi trường

| Thành phần | Cấu hình |
|---|---|
| Máy chủ | Laptop Windows 10/11, Docker Desktop (WSL2, giới hạn 3 GB RAM) |
| Hệ thống | `docker compose --profile app` (app-up): Mosquitto 2.0.20, PostgreSQL 16, backend Spring Boot 3.5 (Java 21, heap 384 MB), frontend nginx |
| Thiết bị thật | ESP32-WROOM-32 (CP2102), MQ-2 qua phân áp 10K/20K, DS18B20, relay 5V + quạt, LED đỏ/xanh, còi qua 2N2222. Firmware `firmware/gas-node` |
| Thiết bị mô phỏng | Python + paho-mqtt (MQTT v5), nói đúng giao thức `iot/v1` như firmware (QoS1, `seq`, `ts`, LWT, `cmd/ack`, `config/ack`) |
| Mạng | WiFi 2.4 GHz cùng mạng LAN với laptop |
| Công cụ | `tests/e2e/run_e2e.py`, `tests/perf/run_perf.py`, `docker stats`, trình duyệt, Serial Monitor (115200) |

Vì sao có thiết bị mô phỏng: dùng được nhiều thiết bị cùng lúc (đo tải), tạo được các tình huống khó làm bằng tay
(bản tin sai định dạng, publish sai topic, trùng lặp, mất mạng đúng thời điểm), và chạy lại được y hệt mỗi lần.
Thiết bị thật được kiểm thử riêng ở mục 6, để chứng minh firmware nói đúng cùng giao thức.

## 3. Truy vết yêu cầu → ca kiểm thử

| Yêu cầu đề bài | Ca kiểm thử |
|---|---|
| 7.1 Đăng nhập và phân quyền | F1, S2, S3, S6 |
| 7.1 Đăng ký, kích hoạt thiết bị | F2, H1 |
| 7.1 Thiết bị gửi telemetry | F3, H2 |
| 7.1 Dashboard cập nhật dữ liệu | F4, H2 |
| 7.1 Gửi và xác nhận lệnh điều khiển | F5, H3 |
| 7.1 Phát hiện thiết bị offline | F7, H6 |
| 7.1 Sinh và xử lý cảnh báo | F8, H5 |
| 7.1 Khóa hoặc ngừng sử dụng thiết bị | F9, H9 |
| 4.5 Người dùng không truy cập thiết bị của người khác | S1, S2 |
| 4.5 Thiết bị đã bị thu hồi không thể tiếp tục gửi dữ liệu | F9, S7, H9 |
| 4.5 API từ chối token không hợp lệ hoặc hết hạn | S3 |
| 4.5 Broker từ chối publish/subscribe sai topic | S4 |
| 4.5 Hệ thống từ chối dữ liệu không hợp lệ | S5 |
| 4.5 Băm mật khẩu, audit log, chống dò mật khẩu | S6, S8 |
| 4.5 Sử dụng TLS/HTTPS, kết nối mã hóa | S9 |
| 4.4b Cấu hình từ xa | F6, H4 |
| 7.2 Độ trễ cảm biến → dashboard | P1, H2 |
| 7.2 Độ trễ lệnh điều khiển | P2, H3 |
| 7.2 Tỷ lệ mất bản tin | P3 |
| 7.2 Mất và khôi phục mạng | P4, H7 |
| 7.2 Bản tin trùng lặp | P5 |
| 7.2 CPU, bộ nhớ | P6 |
| 7.2 Ổn định liên tục | P7 |
| 7.2 Số thiết bị / bản tin xử lý được | P8 |
| 8.4 Kịch bản demo 10 bước | F1–F9, S1, H1–H9 |

## 4. Ca kiểm thử chức năng và bảo mật (tự động – `tests/e2e/run_e2e.py`)

Mỗi lần chạy tự tạo dữ liệu riêng (người dùng `e2e_*_<mã>`, thiết bị `e2e-<mã>`) và tự dọn khi xong
(khóa người dùng test, ngừng sử dụng thiết bị test). Không ảnh hưởng thiết bị thật đang chạy.

| Mã | Ca kiểm thử | Các bước chính | Kết quả mong đợi |
|---|---|---|---|
| F1 | Đăng nhập, phân quyền | Đăng nhập 3 vai trò; sai mật khẩu; gọi API quản trị bằng từng vai trò; VIEWER đăng ký thiết bị; tự đăng ký; đăng xuất | 200 đúng vai trò; 401; chỉ ADMIN được 200, còn lại 403; 403; vai trò mặc định VIEWER; token cũ 401 |
| F2 | Đăng ký, kích hoạt | Đăng ký thiết bị; đăng ký trùng; cấp xác thực; xem lại xác thực; thiết bị kết nối và gửi bản tin đầu tiên | REGISTERED; 409; PROVISIONED, username = clientId = mã thiết bị; không lộ mật khẩu; ACTIVE + ONLINE |
| F3 | Telemetry | Gửi 10 bản tin có giá trị biết trước; truy vấn raw/latest/stats/series/CSV | Lưu đủ, đúng giá trị, đơn vị ppm/C, có `seq`; thống kê đúng min/max; CSV mở được |
| F4 | Dashboard realtime | Client WebSocket/STOMP như trình duyệt; gửi 1 bản tin có giá trị đánh dấu | Bản tin xuất hiện trên kênh realtime; API dashboard hiện ONLINE + giá trị mới |
| F5 | Lệnh + ACK | FAN_SET, PING; lệnh không hỗ trợ; thiết bị không trả lời | SUCCESS + độ trễ + trạng thái quạt mới; lịch sử lưu người gửi; 400; TIMEOUT sau 10 s |
| F6 | Cấu hình từ xa | Đổi chu kỳ/ngưỡng; cấu hình sai | Thiết bị nhận bản tin retained, trả APPLIED đúng phiên bản; 400 |
| F7 | Offline | Ngắt TCP đột ngột (giống mất điện); gửi lệnh; kết nối lại | OFFLINE (LWT) + cảnh báo; 409 DEVICE_OFFLINE; ONLINE lại |
| F8 | Cảnh báo | Gửi gas 1200 ppm; người không quyền xác nhận; chủ thiết bị xác nhận; gas trở lại bình thường; xử lý xong | WARNING + CRITICAL theo luật; WebSocket báo; 403; ACKNOWLEDGED; đánh dấu hết điều kiện; RESOLVED + ghi chú |
| F9 | Khóa / ngừng sử dụng | Khóa không lý do; khóa; kết nối lại; gửi lệnh; mở khóa; ngừng sử dụng; kết nối; xem lịch sử; kích hoạt lại | 400; SUSPENDED + broker ngắt phiên; CONNACK bị từ chối; 409; kết nối lại được; DECOMMISSIONED; bị từ chối vĩnh viễn; lịch sử vẫn còn; 409; audit ghi đủ |
| S1 | Thiết bị của người khác | OWNER khác gọi đọc/ghi/điều khiển/vòng đời; đăng ký kênh realtime | 403 mọi API; không có trong danh sách; WebSocket từ chối; audit ghi DENIED |
| S2 | Người được chia sẻ | Chia sẻ cho VIEWER; VIEWER đọc; VIEWER điều khiển/cấu hình/đổi mật khẩu thiết bị/khóa | Đọc 200; mọi thao tác ghi 403 (kiểm tra ở backend) |
| S3 | Token | Không token; token rác; sửa chữ ký; tự sửa vai trò thành ADMIN; token đúng chữ ký nhưng hết hạn; token đã đăng xuất; WebSocket token sai | Tất cả 401 / bị từ chối |
| S4 | ACL broker | Thiết bị A publish vào topic B; publish vào `cmd`; subscribe `cmd` của B; subscribe `#`; sai mật khẩu; dùng mật khẩu A cho tên B; sai clientId; ẩn danh | PUBACK/SUBACK 0x87; CONNACK bị từ chối; dữ liệu giả không vào CSDL |
| S5 | Dữ liệu không hợp lệ | 9 loại bản tin lỗi (JSON hỏng, thiếu/lệch thời gian, ngoài dải, sai đơn vị, trường lạ, sai deviceId, quá 4 KB); REST sai định dạng | Bộ đếm từ chối tăng đúng lý do; không lưu vào CSDL; 400 kèm lỗi theo trường |
| S6 | Dò mật khẩu | Sai 5 lần; đúng mật khẩu ở lần 6; admin mở khóa | Lần 5 bị khóa (423); vẫn 423; đăng nhập lại được |
| S7 | Thu hồi xác thực | Đổi mật khẩu thiết bị (rotate) | Phiên cũ bị ngắt; mật khẩu cũ bị từ chối; mật khẩu mới dùng được |
| S8 | Audit + lưu trữ | Đọc audit log; danh sách người dùng; đọc CSDL | Audit có đăng ký, cấp xác thực, lệnh, cấu hình, rotate, chia sẻ; API không trả băm mật khẩu; CSDL lưu BCrypt `$2a$12$` |
| S9 | Kết nối mã hóa TLS | Thiết bị kết nối MQTT 8883 có kiểm tra CA; gửi MQTT không mã hóa vào 8883; client chỉ tin CA công cộng; dò cổng từ IP LAN; truy cập web HTTP, HTTPS | TLS 1.2/1.3, CONNACK 0, dữ liệu được lưu; kết nối không mã hóa không có CONNACK; chứng chỉ không tin cậy bị từ chối; từ LAN cổng 1883 đóng, 8883 mở; HTTP trả 301 sang HTTPS; HTTPS 200; đăng nhập qua HTTPS được |

Chạy thêm `GG_FULL=1` để kiểm tra watchdog phát hiện offline khi thiết bị treo mà không có LWT (chờ khoảng 40 s).

## 5. Chỉ tiêu phi chức năng (`tests/perf/run_perf.py`)

| Mã | Chỉ tiêu | Phương pháp đo | Mục tiêu |
|---|---|---|---|
| P1 | Độ trễ cảm biến → dashboard | 200 bản tin, client WebSocket như trình duyệt, độ trễ = lúc nhận − `ts` | P95 ≤ 1000 ms, nhận đủ |
| P2 | Độ trễ lệnh | 50 lệnh qua REST, `latencyMs` (gửi → ACK) và khứ hồi API | P95 khứ hồi ≤ 1000 ms, 100 % thành công |
| P3 | Tỷ lệ mất bản tin | 1000 bản tin, 50 bản tin/giây, QoS1 và QoS0, đếm trong CSDL | QoS1: 0 % |
| P4 | Mất và khôi phục mạng | (a) ngắt đột ngột + 20 s đệm + gửi bù; (b) khởi động lại broker khi đang gửi; (c) khởi động lại backend khi đang gửi | OFFLINE ≤ 30 s; không mất bản tin |
| P5 | Bản tin trùng | Gửi 200 bản tin rồi gửi lại y hệt | CSDL lưu đúng 200, không trùng |
| P6 | CPU / bộ nhớ | `docker stats` mỗi ~2 s lúc nghỉ và theo từng mức tải | RAM backend ≤ 600 MiB |
| P7 | Ổn định | 5 thiết bị × 1 bản tin/giây trong 10 phút, theo dõi từng phút | Mất 0 %, không lỗi, RAM không tăng liên tục |
| P8 | Số thiết bị | 10 / 50 / 100 thiết bị đồng thời, 1 bản tin/giây/thiết bị, 60 s mỗi mức | Mất 0 %, P95 ≤ 1000 ms |

Ghi chú về mục tiêu: hệ thống báo động rò rỉ gas cần phản ứng trong vài giây. Ngưỡng 1 s cho dashboard và lệnh là đủ
cho người vận hành; luật báo động tại thiết bị (còi, quạt) chạy ngay trên ESP32 nên không phụ thuộc mạng.

## 6. Kiểm thử thủ công với thiết bị thật (ESP32)

Ghi kết quả và đường dẫn ảnh/video vào [`docs/hardware-test-results.md`](hardware-test-results.md).

| Mã | Ca kiểm thử | Thao tác | Kết quả mong đợi | Bằng chứng |
|---|---|---|---|---|
| H1 | Kích hoạt thiết bị thật | Đăng ký + cấp xác thực trên web, nạp `secrets.h`, cấp nguồn | Serial: `Đã kết nối broker`; web: ACTIVE, ONLINE, firmware 1.0.0 | Ảnh Serial + web |
| H2 | Telemetry + realtime | Để chạy 2 phút | Gas/nhiệt độ cập nhật mỗi chu kỳ không cần F5; tab Dữ liệu có đường biểu đồ; cột độ trễ TB < 1 s | Ảnh dashboard + tab Dữ liệu |
| H3 | Điều khiển quạt + còi | Tab Điều khiển: Bật / Tắt / Tự động cho quạt và còi | Relay kêu, quạt quay/dừng, còi kêu/im; web *Thành công* + độ trễ | Video ngắn |
| H4 | Cấu hình từ xa | Đổi chu kỳ 3 s, ngưỡng 300 ppm | Serial `Áp dụng cấu hình v…`; web *Đã áp dụng* | Ảnh |
| H5 | Cảnh báo gas | Xịt ít gas bật lửa (không đánh lửa) cách MQ-2 ~5 cm | LED đỏ + còi + quạt tự bật; web cảnh báo; email cảnh báo; xác nhận và xử lý trên web | Video + ảnh email |
| H6 | Offline | Rút USB 30 s rồi cắm lại | ~20 s sau: Ngoại tuyến + cảnh báo LWT; cắm lại tự online | Ảnh |
| H7 | Mất mạng, gửi bù | `dc stop mosquitto` 40 s rồi `dc start mosquitto` | LED xanh nháy chậm; báo động tại chỗ vẫn chạy; Serial `Gửi bù …`; biểu đồ không hụt | Ảnh Serial + biểu đồ |
| H8 | Lỗi cảm biến | Rút dây DATA DS18B20 (i45 → D4) 20 s | LED đỏ nháy; web *Lỗi* + cảnh báo lỗi cảm biến; cắm lại tự hết | Ảnh |
| H9 | Khóa / thu hồi | Tạm khóa trên web, rồi mở khóa | Serial `Broker TỪ CHỐI (mã 5)`, web *Tạm ngưng*; mở khóa thì tự kết nối lại | Ảnh Serial |
| H10 | Quá nhiệt | Nhúng đầu DS18B20 vào nước ~60 °C | > 45 °C: LED đỏ + còi + quạt; > 50 °C: cảnh báo trên web | Video |

An toàn khi làm H5: lượng gas rất nhỏ, nơi thoáng, không có lửa.

## 7. Tiêu chí đạt

- Chức năng/bảo mật: 100 % ca F và S đạt.
- Phi chức năng: mọi chỉ tiêu đạt mục tiêu ở mục 5. Chỉ tiêu nào chưa đạt phải có giải thích và hướng khắc phục trong báo cáo.
- Phần cứng: H1–H9 đạt, có ảnh/video làm bằng chứng.

## 8. Cách chạy và nơi lưu bằng chứng

```powershell
cd E:\IOT\iot-gas-monitor
pip install -r tests\requirements.txt
python tests\e2e\run_e2e.py            # ~3 phút
python tests\perf\run_perf.py          # ~20 phút (hoặc --quick ~8 phút)
python tests\make_report.py            # gộp kết quả -> docs\test-report.md
```

- Báo cáo từng lần chạy: `tests/reports/e2e-<thời điểm>.md|json`, `tests/reports/perf-<thời điểm>.md|json` (commit để làm bằng chứng).
- Báo cáo tổng hợp cho chương "Kiểm thử và đánh giá": `docs/test-report.md`.
- Kiểm thử phần cứng: `docs/hardware-test-results.md` + ảnh trong `docs/images/tests/`.
