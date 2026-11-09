#!/usr/bin/env python3
"""
Client WebSocket/STOMP đóng vai DASHBOARD để kiểm thử realtime và đo độ trễ (mục 7.2).

  * Đăng nhập REST lấy JWT -> CONNECT STOMP kèm "Authorization: Bearer <token>".
  * Subscribe /topic/devices/{code} cho từng thiết bị (mặc định: mọi thiết bị mình được xem).
  * In mọi sự kiện: telemetry, connectivity, lifecycle, alert, command, config, event.
  * Với telemetry: độ trễ = thời điểm client nhận - thời điểm cảm biến đo (ts).
    Khi dừng (Ctrl+C hoặc --duration) in thống kê p50 / p95 / max / trung bình.

Lưu ý đo độ trễ: đồng hồ thiết bị (NTP) và máy chạy client phải đồng bộ. Khi simulator và client
chạy cùng một máy thì dùng chung đồng hồ -> số đo chính xác.

Ví dụ:
  py ws_client.py --username chu1 --password Chu12345
  py ws_client.py --username chu1 --password Chu12345 --devices esp32-01 --duration 120
  py ws_client.py --token abc.def.ghi            (kiểm thử: token sai phải bị từ chối)
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime

try:
    import websocket
except ImportError:
    sys.exit("Thiếu thư viện websocket-client. Chạy: pip install -r requirements.txt")


def login(api: str, username: str, password: str) -> str:
    req = urllib.request.Request(f"{api}/api/auth/login", method="POST",
                                 data=json.dumps({"username": username, "password": password}).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.load(r)["token"]
    except urllib.error.HTTPError as e:
        sys.exit(f"Đăng nhập thất bại [{e.code}]: {e.read().decode('utf-8', 'replace')}")


def my_devices(api: str, token: str) -> list[str]:
    req = urllib.request.Request(f"{api}/api/devices", headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return [d["deviceCode"] for d in json.load(r)]


def frame(command: str, headers: dict[str, str], body: str = "") -> str:
    return command + "\n" + "".join(f"{k}:{v}\n" for k, v in headers.items()) + "\n" + body + "\x00"


def parse_frames(buffer: str) -> tuple[list[tuple[str, dict, str]], str]:
    out = []
    while "\x00" in buffer:
        raw, buffer = buffer.split("\x00", 1)
        raw = raw.lstrip("\r\n")                      # bỏ heart-beat (dòng trống)
        if not raw:
            continue
        head, _, body = raw.partition("\n\n")
        lines = head.split("\n")
        headers = dict(line.split(":", 1) for line in lines[1:] if ":" in line)
        out.append((lines[0].strip(), headers, body))
    return out, buffer


def iso_to_ms(s: str) -> float:
    return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp() * 1000


def describe(msg: dict) -> str:
    t = msg.get("type")
    if t == "telemetry":
        r = msg.get("readings", {})
        return "  ".join(f"{k}={v['value']}{v['unit']}" for k, v in r.items())
    if t in ("connectivity", "lifecycle"):
        return f"{msg.get('from')} -> {msg.get('to')} ({msg.get('reason') or msg.get('actor')})"
    if t == "alert":
        a = msg["alert"]
        return f"{msg.get('change')}: [{a['severity']}] {a['message']} (status={a['status']})"
    if t == "command":
        c = msg["command"]
        return f"{c['action']} {c['status']} latency={c.get('latencyMs')}ms {c.get('resultMessage') or ''}"
    if t == "config":
        c = msg["config"]
        return f"v{c['configVersion']} applied=v{c['appliedVersion']} {c['status']} {c.get('message') or ''}"
    if t == "event":
        return f"{msg.get('eventType')} {msg.get('data')}"
    return json.dumps(msg, ensure_ascii=False)


def main() -> None:
    p = argparse.ArgumentParser(description="Dashboard giả lập: nhận realtime qua WebSocket/STOMP và đo độ trễ")
    p.add_argument("--api", default="http://localhost:8080")
    p.add_argument("--username")
    p.add_argument("--password")
    p.add_argument("--token", help="Dùng token có sẵn thay vì đăng nhập")
    p.add_argument("--devices", help="Danh sách mã thiết bị, cách nhau bằng dấu phẩy (mặc định: tất cả được xem)")
    p.add_argument("--duration", type=float, default=0, help="Tự dừng sau N giây (0 = đến khi Ctrl+C)")
    p.add_argument("--quiet", action="store_true", help="Không in từng bản tin telemetry")
    args = p.parse_args()

    token = args.token or (login(args.api, args.username, args.password) if args.username else None)
    if not token:
        sys.exit("Cần --username/--password hoặc --token")
    codes = args.devices.split(",") if args.devices else (my_devices(args.api, token) if not args.token else [])
    if not codes and not args.token:
        sys.exit("Tài khoản này chưa có thiết bị nào để xem")

    ws_url = args.api.replace("http", "ws", 1) + "/ws"
    ws = websocket.create_connection(ws_url, timeout=5)
    ws.send(frame("CONNECT", {"accept-version": "1.2", "host": "localhost",
                              "heart-beat": "0,10000", "Authorization": f"Bearer {token}"}))
    buf, connected = "", False
    deadline = time.time() + 5
    while not connected and time.time() < deadline:
        buf += ws.recv()
        frames, buf = parse_frames(buf)
        for cmd, headers, body in frames:
            if cmd == "CONNECTED":
                connected = True
            elif cmd == "ERROR":
                print(f"[TỪ CHỐI KẾT NỐI] {headers.get('message', '')} {body}".strip())
                sys.exit(2)
    if not connected:
        sys.exit("Không nhận được CONNECTED")
    print(f"Đã kết nối {ws_url}. Subscribe: {', '.join(codes) or '(không có)'}")
    for i, code in enumerate(codes):
        ws.send(frame("SUBSCRIBE", {"id": f"sub-{i}", "destination": f"/topic/devices/{code}"}))

    latencies: list[float] = []
    counts: dict[str, int] = {}
    started = time.time()
    ws.settimeout(1)
    try:
        while not args.duration or time.time() - started < args.duration:
            try:
                data = ws.recv()
            except websocket.WebSocketTimeoutException:
                continue
            if not data:
                continue
            buf += data
            frames, buf = parse_frames(buf)
            for cmd, headers, body in frames:
                if cmd == "ERROR":
                    print(f"[TỪ CHỐI] {headers.get('message', '')}")
                    raise SystemExit(3)
                if cmd != "MESSAGE":
                    continue
                now_ms = time.time() * 1000
                msg = json.loads(body)
                t = msg.get("type", "?")
                counts[t] = counts.get(t, 0) + 1
                extra = ""
                if t == "telemetry" and msg.get("ts"):
                    lat = now_ms - iso_to_ms(msg["ts"])
                    latencies.append(lat)
                    extra = f"  | trễ cảm biến->dashboard = {lat:.0f} ms"
                if t != "telemetry" or not args.quiet:
                    print(f"{time.strftime('%H:%M:%S')} [{msg.get('deviceCode')}] {t:<12} {describe(msg)}{extra}",
                          flush=True)
    except KeyboardInterrupt:
        pass
    finally:
        try:
            ws.send(frame("DISCONNECT", {}))
            ws.close()
        except Exception:
            pass

    print("\n=== THỐNG KÊ REALTIME ===")
    print("Số bản tin theo loại:", counts or "(không có)")
    if latencies:
        s = sorted(latencies)
        p95 = s[min(len(s) - 1, int(0.95 * (len(s) - 1)))]
        print(f"Độ trễ cảm biến -> dashboard (ms): n={len(s)} trung bình={statistics.mean(s):.1f} "
              f"p50={statistics.median(s):.1f} p95={p95:.1f} max={s[-1]:.1f}")


if __name__ == "__main__":
    main()
