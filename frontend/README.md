# Frontend – GasGuard (React + Vite)

Giao diện web của hệ thống giám sát rò rỉ khí gas.

## Chạy môi trường dev

Yêu cầu: Node.js 18+ (khuyến nghị 20 LTS), backend đang chạy ở `http://localhost:8080`.

```powershell
cd frontend
npm install
npm run dev
```

Mở http://localhost:5173. Vite proxy `/api` và `/ws` sang backend
(đổi địa chỉ bằng biến môi trường `BACKEND_URL`, ví dụ `$env:BACKEND_URL="http://192.168.1.10:8080"`).

Build bản phát hành: `npm run build` → thư mục `dist/`.

## Màn hình (mục 4.6 đề bài)

| Màn hình | Đường dẫn | Vai trò |
|---|---|---|
| Đăng nhập / Đăng ký | `/login`, `/register` | Tất cả |
| Dashboard thời gian thực | `/` | Tất cả |
| Danh sách / đăng ký thiết bị | `/devices` | Tất cả (đăng ký: ADMIN, OWNER) |
| Chi tiết thiết bị: tổng quan, dữ liệu & biểu đồ, điều khiển, cấu hình, cảnh báo, xác thực MQTT, chia sẻ, lịch sử | `/devices/:code?tab=` | Theo quyền trên thiết bị |
| Cảnh báo (lọc, xác nhận, xử lý) | `/alerts` | Tất cả (xử lý: người có quyền quản lý) |
| Luật cảnh báo | `/alert-rules` | Tất cả xem; ADMIN luật toàn cục, OWNER luật thiết bị mình |
| Quản lý người dùng | `/users` | ADMIN |
| Audit log | `/audit` | ADMIN |
| Hệ thống (thống kê tiếp nhận MQTT, độ trễ) | `/system` | ADMIN |
| Tài khoản (thông tin, đổi mật khẩu) | `/account` | Tất cả |

Mọi kiểm tra quyền đều do backend quyết định; giao diện chỉ ẩn/vô hiệu hóa nút cho dễ dùng.
Token JWT lưu ở `localStorage` (`gasguard_token`); khi backend trả 401 (hết hạn / bị thu hồi) giao diện tự đưa về trang đăng nhập.
