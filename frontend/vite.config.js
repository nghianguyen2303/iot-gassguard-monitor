import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Khi dev: trình duyệt gọi cùng origin (localhost:5173), Vite chuyển tiếp /api và /ws sang backend.
// Nhờ vậy không cần cấu hình CORS phức tạp và WebSocket đi qua cùng cổng.
const backend = process.env.BACKEND_URL || 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: backend, changeOrigin: false },
      '/ws': { target: backend.replace(/^http/, 'ws'), ws: true, changeOrigin: false },
    },
  },
});
