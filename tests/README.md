# Kiểm thử GasGuard

Kế hoạch kiểm thử (ca nào kiểm tra yêu cầu nào, mục tiêu đo): [`docs/test-plan.md`](../docs/test-plan.md).

| Thư mục | Nội dung | Yêu cầu đề bài |
|---|---|---|
| `e2e/run_e2e.py` | 18 ca tự động: chức năng F1–F9, bảo mật S1–S9 | 7.1, 4.5 |
| `perf/run_perf.py` | 8 chỉ tiêu P1–P8: độ trễ, mất bản tin, mất mạng, trùng lặp, CPU/RAM, ổn định, số thiết bị | 7.2 |
| `make_report.py` | Gộp kết quả mới nhất → `docs/test-report.md` | 7, 8.2 |
| `reports/` | Báo cáo từng lần chạy (`.md` để đọc, `.json` số liệu thô) – **commit làm bằng chứng** | 7 |
| `common/` | Thư viện dùng chung: REST, thiết bị MQTT mô phỏng, client WebSocket, ghi báo cáo | |
| `simulator/` | Trình mô phỏng thiết bị chạy tay (bước 5) | |
| `realtime/` | Client WebSocket xem dữ liệu realtime (bước 11) | |

Thiết bị trong các bài test được **mô phỏng bằng MQTT đúng giao thức firmware** (QoS1, `seq`, `ts`, LWT,
`cmd/ack`, `config/ack`), nhờ vậy tạo được nhiều thiết bị và các tình huống khó làm bằng tay. Kiểm thử với ESP32
thật làm theo mục 6 của kế hoạch và ghi vào `docs/hardware-test-results.md`.

## Chạy

Hệ thống phải đang chạy (`app-up`, hoặc backend IntelliJ + `dc up -d`). PowerShell:

```powershell
cd E:\IOT\iot-gas-monitor
pip install -r tests\requirements.txt      # lần đầu (psutil không bắt buộc)

python tests\e2e\run_e2e.py                 # ~5 phút
python tests\perf\run_perf.py --quick       # ~8 phút (ổn định 2 phút, tải 10/50 thiết bị)
python tests\perf\run_perf.py               # ~40 phút (ổn định 10 phút, tải 10/50/100 thiết bị) – dùng cho báo cáo
python tests\make_report.py                 # tạo docs\test-report.md
```

Script tự đọc mật khẩu admin (`APP_ADMIN_PASSWORD`) và `JWT_SECRET` (để tạo token hết hạn hợp lệ cho ca S3)
từ file `.env`, **không in ra màn hình, không ghi vào báo cáo**.

Tùy chọn hay dùng:

| Lệnh / biến | Ý nghĩa |
|---|---|
| `run_perf.py --only P1,P2,P5` | Chỉ đo một số chỉ tiêu |
| `run_perf.py --stability-min 60` | Chạy ổn định 60 phút |
| `run_perf.py --load 10,50,100,200` | Các mức số thiết bị cho P8 |
| `$env:GG_FULL="1"` (trước `run_e2e.py`) | Thêm ca watchdog: thiết bị treo không có LWT (chờ thêm ~40 s) |
| `$env:GG_ADMIN_PASSWORD="..."` | Khi đã đổi mật khẩu admin trên web |
| `$env:GG_API`, `GG_MQTT_HOST`, `GG_MQTT_PORT` | Đổi địa chỉ (mặc định `http://127.0.0.1:8080`, `127.0.0.1:1883`; `localhost` tự đổi thành `127.0.0.1` để tránh trễ ~2 s do Windows thử IPv6 trước) |
| `$env:GG_CA_CERT`, `GG_MQTTS_PORT`, `GG_WEB_HTTP`, `GG_WEB_HTTPS` | Cho ca S9 (TLS): CA (mặc định `deployment\certs\server\ca.crt`), cổng MQTT/TLS 8883, địa chỉ web `http://127.0.0.1:8088` / `https://127.0.0.1:8443` |
| `$env:GG_BROKER_RESTART_CMD`, `GG_BACKEND_RESTART_CMD` | Lệnh khởi động lại cho P4 (mặc định `docker restart iot-mosquitto` / `iot-backend`) |

## Lưu ý

- Mỗi lần chạy tạo dữ liệu riêng: người dùng `e2e_*_<mã>` (cuối bài bị khóa lại), thiết bị `e2e-*`, `perf-*`
  (cuối bài chuyển *Ngừng sử dụng*, không còn hiện trong danh sách). Thiết bị thật `esp32-001` không bị đụng tới.
- **P4 khởi động lại broker và backend**: ESP32 thật đang chạy sẽ mất kết nối vài giây rồi tự kết nối lại và gửi bù.
  Không chạy `run_perf.py` trong lúc demo.
- S9 (TLS) cần chứng chỉ đã tạo bằng `deployment/certs/gen-certs.sh`; chưa có thì S9 báo *Bỏ qua*. Phần HTTPS của S9 cần
  frontend chạy bằng Docker (`app-up`). Các ca khác dùng cổng MQTT 1883 trên `127.0.0.1` (chỉ mở cho chính máy chủ).
- P6 đo CPU/RAM bằng `docker stats` (cần hệ thống chạy bằng Docker); không có Docker thì dùng `psutil` theo tiến trình.
- Độ lệch đồng hồ giữa Windows và máy ảo WSL2 của Docker được đo và trừ đi khi > 50 ms (ghi trong báo cáo).
- Lỗi `Không kết nối được broker` → kiểm tra `dc ps`; lỗi đăng nhập admin → đặt `GG_ADMIN_PASSWORD`.
