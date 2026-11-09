# ---------------------------------------------------------------------------
# Image frontend: build React bằng Node, phục vụ file tĩnh bằng nginx.
# nginx đồng thời là reverse proxy: /api và /ws -> backend, nên trình duyệt
# chỉ làm việc với MỘT origin (không cần CORS, WebSocket đi cùng cổng).
# ---------------------------------------------------------------------------
FROM node:20.18.1-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:1.27.3-alpine
# nginx.conf là template: lúc khởi động image nginx thay ${WEB_HTTPS_PORT} rồi ghi ra conf.d/default.conf
ENV WEB_HTTPS_PORT=8443
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist /usr/share/nginx/html
# 80: chỉ chuyển hướng sang HTTPS; 443: HTTPS (chứng chỉ gắn vào /etc/nginx/certs lúc chạy, không đóng vào image)
EXPOSE 80 443
HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
    CMD wget -qO- http://127.0.0.1/healthz || exit 1
