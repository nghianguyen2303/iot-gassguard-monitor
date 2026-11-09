"""
Thư viện dùng chung cho bộ kiểm thử GasGuard (tests/e2e, tests/perf).

  Config        đọc cấu hình từ biến môi trường / file .env ở gốc repo (không in bí mật ra màn hình)
  Api           gọi REST API, trả (status, json)
  SimDevice     thiết bị MQTT mô phỏng nói đúng giao thức iot/v1 (giống firmware ESP32), MQTT v5
                để broker trả MÃ LÝ DO rõ ràng khi bị từ chối (0x87 Not authorized ...)
  StompClient   client WebSocket/STOMP giống hệt dashboard web (JWT trong CONNECT)
  Report        ghi kết quả ra tests/reports/*.md + *.json làm bằng chứng
"""
from __future__ import annotations

import base64
import datetime as dt
import hashlib
import hmac
import json
import re
import os
import platform
import socket
import ssl
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from typing import Any, Callable

import paho.mqtt.client as mqtt
from paho.mqtt.enums import CallbackAPIVersion
from paho.mqtt.packettypes import PacketTypes
from paho.mqtt.properties import Properties
import websocket

# PowerShell 5.1 / cmd trên Windows mặc định không phải UTF-8 -> ép UTF-8 để in tiếng Việt và ✔/✘
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

REPO_ROOT = Path(__file__).resolve().parents[2]
REPORT_DIR = REPO_ROOT / "tests" / "reports"
VN = dt.timezone(dt.timedelta(hours=7))


# ============================================================================ cấu hình
def _read_env_file(path: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    if not path.exists():
        return out
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def _ipv4_loopback(value: str) -> str:
    """Đổi 'localhost' -> '127.0.0.1' (cả trong URL), giữ nguyên địa chỉ khác."""
    return re.sub(r"(^|//)localhost(?=[:/]|$)", r"\g<1>127.0.0.1", value)


class Config:
    """Ưu tiên biến môi trường, sau đó file .env ở gốc repo."""

    def __init__(self) -> None:
        env = _read_env_file(REPO_ROOT / ".env")
        get = lambda k, d=None: os.environ.get(k) or env.get(k) or d  # noqa: E731
        # Dùng 127.0.0.1 thay cho "localhost": trên Windows, Python thử IPv6 (::1) trước, còn Docker Desktop chỉ mở
        # cổng ở IPv4 -> mỗi lần kết nối phải chờ ~2 s mới quay về IPv4, làm sai số liệu độ trễ (P2) và độ lệch đồng hồ.
        self.api = _ipv4_loopback(get("GG_API", "http://127.0.0.1:8080")).rstrip("/")
        self.ws = _ipv4_loopback(get("GG_WS", self.api.replace("http", "ws", 1) + "/ws"))
        self.mqtt_host = _ipv4_loopback(get("GG_MQTT_HOST", "127.0.0.1"))
        self.mqtt_port = int(get("GG_MQTT_PORT", "1883"))
        # TLS (mục 4.5): broker 8883, web HTTPS. CA của nhóm do deployment/certs/gen-certs.sh tạo.
        self.mqtts_port = int(get("GG_MQTTS_PORT", get("MQTT_TLS_PORT", "8883")))
        self.ca_cert = get("GG_CA_CERT", str(REPO_ROOT / "deployment" / "certs" / "server" / "ca.crt"))
        self.web_http = _ipv4_loopback(get("GG_WEB_HTTP", f"http://127.0.0.1:{get('WEB_PORT', '8088')}")).rstrip("/")
        self.web_https = _ipv4_loopback(get("GG_WEB_HTTPS", f"https://127.0.0.1:{get('WEB_HTTPS_PORT', '8443')}")).rstrip("/")
        self.admin_user = get("GG_ADMIN_USER", "admin")
        self.admin_password = get("GG_ADMIN_PASSWORD") or get("APP_ADMIN_PASSWORD")
        self.jwt_secret = get("GG_JWT_SECRET") or get("JWT_SECRET")
        if not self.admin_password:
            raise SystemExit("Thiếu mật khẩu admin: đặt APP_ADMIN_PASSWORD trong .env hoặc biến GG_ADMIN_PASSWORD")

    def describe(self) -> dict[str, str]:
        return {"API": self.api, "WebSocket": self.ws, "MQTT": f"{self.mqtt_host}:{self.mqtt_port}",
                "MQTT/TLS": f"{self.mqtt_host}:{self.mqtts_port}", "Web HTTPS": self.web_https,
                "Máy chạy test": f"{platform.system()} {platform.release()} / Python {platform.python_version()}"}


def now_ms() -> int:
    return int(time.time() * 1000)


def iso(ts: float | None = None) -> str:
    return dt.datetime.fromtimestamp(ts if ts is not None else time.time(), dt.timezone.utc).isoformat().replace("+00:00", "Z")


def iso_to_ms(s: str) -> float:
    return dt.datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp() * 1000


def run_id() -> str:
    return dt.datetime.now(VN).strftime("%m%d%H%M%S")


def wait_until(pred: Callable[[], Any], timeout: float, step: float = 0.2) -> Any:
    end = time.time() + timeout
    while True:
        v = pred()
        if v:
            return v
        if time.time() >= end:
            return v
        time.sleep(step)


def pct(values: list[float], p: float) -> float:
    if not values:
        return float("nan")
    s = sorted(values)
    k = (len(s) - 1) * p / 100
    f = int(k)
    c = min(f + 1, len(s) - 1)
    return s[f] + (s[c] - s[f]) * (k - f)


def summary(values: list[float]) -> dict[str, float]:
    if not values:
        return {"n": 0}
    return {"n": len(values), "min": round(min(values), 1), "avg": round(statistics.fmean(values), 1),
            "p50": round(pct(values, 50), 1), "p95": round(pct(values, 95), 1), "max": round(max(values), 1)}


# ============================================================================ REST
class Api:
    def __init__(self, cfg: Config) -> None:
        self.base = cfg.api

    def call(self, method: str, path: str, body: Any = None, token: str | None = None,
             timeout: float = 30) -> tuple[int, Any]:
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                raw = r.read()
                return r.status, (json.loads(raw) if raw and r.headers.get_content_type() == "application/json" else raw.decode("utf-8", "replace"))
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                return e.code, json.loads(raw) if raw else None
            except ValueError:
                return e.code, raw.decode("utf-8", "replace")
        except (urllib.error.URLError, ConnectionError, TimeoutError, socket.timeout) as e:
            return 0, f"không kết nối được backend: {getattr(e, 'reason', e)}"   # backend đang khởi động lại / tắt

    def login(self, username: str, password: str) -> str:
        s, b = self.call("POST", "/api/auth/login", {"username": username, "password": password})
        if s != 200:
            raise RuntimeError(f"Đăng nhập {username} thất bại: {s} {b}")
        return b["token"]


def clock_offset_ms(api: "Api", token: str, n: int = 9, max_rtt_ms: float = 250) -> tuple[float | None, float]:
    """Độ lệch đồng hồ backend − máy chạy test (ms), đo kiểu NTP qua `generatedAt` của /api/dashboard.
    Cần khi backend chạy trong Docker/WSL2 còn test chạy trên Windows: hai đồng hồ có thể lệch nhau.
    Trả về (độ lệch, thời gian khứ hồi ngắn nhất). Độ lệch = None khi mọi lần đo đều khứ hồi quá `max_rtt_ms`
    (phép đo kiểu NTP chỉ đúng khi khứ hồi ngắn và đối xứng) -> không được dùng để hiệu chỉnh."""
    samples = []
    for _ in range(n):
        t0 = time.time() * 1000
        st, b = api.call("GET", "/api/dashboard", token=token)
        t1 = time.time() * 1000
        if st == 200 and isinstance(b, dict) and b.get("generatedAt"):
            samples.append((t1 - t0, iso_to_ms(b["generatedAt"]) - (t0 + t1) / 2))
    if not samples:
        return None, float("nan")
    samples.sort()                                   # lấy các mẫu có thời gian khứ hồi ngắn nhất (chính xác nhất)
    best_rtt = samples[0][0]
    good = [o for rtt, o in samples if rtt <= max_rtt_ms]
    if not good:
        return None, best_rtt
    return statistics.median(good[: max(1, len(good) // 2)]), best_rtt


def forge_expired_token(valid_token: str, secret: str) -> str:
    """Tạo JWT giống hệt token thật nhưng đã hết hạn 10 phút (ký bằng đúng JWT_SECRET) -> chỉ khác 'exp'."""
    h64, p64, _ = valid_token.split(".")
    pad = lambda s: s + "=" * (-len(s) % 4)  # noqa: E731
    header = json.loads(base64.urlsafe_b64decode(pad(h64)))
    payload = json.loads(base64.urlsafe_b64decode(pad(p64)))
    now = int(time.time())
    payload["iat"], payload["exp"] = now - 3600, now - 600
    enc = lambda o: base64.urlsafe_b64encode(json.dumps(o, separators=(",", ":")).encode()).rstrip(b"=").decode()  # noqa: E731
    signing = f"{enc(header)}.{enc(payload)}"
    algo = {"HS256": hashlib.sha256, "HS384": hashlib.sha384, "HS512": hashlib.sha512}[header["alg"]]
    sig = base64.urlsafe_b64encode(hmac.new(secret.encode(), signing.encode(), algo).digest()).rstrip(b"=").decode()
    return f"{signing}.{sig}"


# ============================================================================ thiết bị MQTT mô phỏng
REASON_NOT_AUTHORIZED = 135  # MQTT v5 0x87


class SimDevice:
    """Thiết bị mô phỏng: publish telemetry/heartbeat/status, tự trả cmd/ack và config/ack như firmware."""

    def __init__(self, cfg: Config, code: str, password: str, keepalive: int = 10, auto_ack: bool = True,
                 client_id: str | None = None, tls: ssl.SSLContext | None = None, port: int | None = None) -> None:
        """tls: kết nối MQTT qua TLS bằng SSLContext này (cổng mặc định cfg.mqtts_port), giống ESP32 với MQTT_USE_TLS 1."""
        self.cfg, self.code, self.password = cfg, code, password
        self.port = port or (cfg.mqtts_port if tls else cfg.mqtt_port)
        self.auto_ack = auto_ack
        self.keepalive = keepalive
        self.base = f"iot/v1/devices/{code}"
        self.seq = 0
        self.fan = False
        self.connect_rc: int | None = None
        self.connected = threading.Event()
        self.connack = threading.Event()
        self.sub_codes: list[int] = []
        self.suback = threading.Event()
        self.pub_codes: dict[int, int] = {}
        self.pub_events: dict[int, threading.Event] = {}
        self.cmds: list[dict] = []
        self.configs: list[dict] = []
        self.disconnect_rc: int | None = None
        self.lock = threading.Lock()
        c = mqtt.Client(callback_api_version=CallbackAPIVersion.VERSION2, client_id=client_id or code,
                        protocol=mqtt.MQTTv5)
        c.username_pw_set(code, password)
        if tls is not None:
            c.tls_set_context(tls)
        c.will_set(f"{self.base}/status", json.dumps({"deviceId": code, "state": "OFFLINE", "reason": "LWT"}),
                   qos=1, retain=True)
        c.on_connect = self._on_connect
        c.on_subscribe = self._on_subscribe
        c.on_publish = self._on_publish
        c.on_message = self._on_message
        c.on_disconnect = self._on_disconnect
        self.client = c

    # ---------------------------------------------------------------- callbacks
    def _on_connect(self, client, userdata, flags, rc, props=None):
        self.connect_rc = rc.value
        self.connack.set()
        if rc.value == 0:
            self.connected.set()
            client.subscribe([(f"{self.base}/cmd", 1), (f"{self.base}/config", 1)])

    def _on_subscribe(self, client, userdata, mid, rcs, props=None):
        self.sub_codes = [r.value for r in rcs]
        self.suback.set()

    def _on_publish(self, client, userdata, mid, rc, props=None):
        with self.lock:
            self.pub_codes[mid] = rc.value
            ev = self.pub_events.get(mid)
        if ev:
            ev.set()

    def _on_disconnect(self, client, userdata, flags, rc, props=None):
        self.disconnect_rc = rc.value
        self.connected.clear()

    def _on_message(self, client, userdata, msg):
        try:
            body = json.loads(msg.payload or b"{}")
        except ValueError:
            return
        if msg.topic.endswith("/cmd"):
            self.cmds.append(body)
            if self.auto_ack:
                on = (body.get("params") or {}).get("on")
                if body.get("action") == "FAN_SET" and isinstance(on, bool):
                    self.fan = on
                ack = {"commandId": body.get("commandId"), "result": "SUCCESS", "message": "ok (sim)",
                       "state": {"fan": self.fan, "fanMode": "MANUAL", "buzzer": False, "buzzerMode": "AUTO"},
                       "ts": now_ms()}
                client.publish(f"{self.base}/cmd/ack", json.dumps(ack), qos=1)
        elif msg.topic.endswith("/config") and msg.payload:
            self.configs.append(body)
            ack = {"configVersion": body.get("configVersion"), "result": "APPLIED", "message": "OK",
                   "applied": body, "ts": now_ms()}
            client.publish(f"{self.base}/config/ack", json.dumps(ack), qos=1)

    # ---------------------------------------------------------------- điều khiển
    def connect(self, timeout: float = 8, status_online: bool = True) -> int | None:
        """Trả mã CONNACK (0 = thành công, 135 = Not authorized). Không tự kết nối lại khi bị từ chối."""
        self.connack.clear()
        self.connect_rc = None
        try:
            self.client.connect(self.cfg.mqtt_host, self.port, keepalive=self.keepalive)
        except OSError as e:   # gồm cả ssl.SSLError (sai CA, sai tên máy chủ trong chứng chỉ...)
            raise RuntimeError(f"Không kết nối được broker {self.cfg.mqtt_host}:{self.port}: {e}") from e
        self.client.loop_start()
        self.connack.wait(timeout)
        if self.connect_rc != 0:
            self.client.loop_stop()
            try:
                self.client.disconnect()
            except Exception:  # noqa: BLE001
                pass
            return self.connect_rc
        self.suback.wait(5)
        if status_online:
            self.publish(f"{self.base}/status", {"deviceId": self.code, "state": "ONLINE", "ts": now_ms(),
                                                 "fw": "test-1.0", "hw": "sim"}, qos=1, retain=True)
        return 0

    def publish(self, topic: str, body: Any, qos: int = 1, retain: bool = False, wait: float = 5) -> int | None:
        """Publish và chờ PUBACK (QoS1). Trả mã lý do PUBACK (0 = nhận, 135 = Not authorized)."""
        payload = body if isinstance(body, (bytes, str)) else json.dumps(body)
        ev = threading.Event()
        info = self.client.publish(topic, payload, qos=qos, retain=retain)
        if qos == 0:
            return 0
        with self.lock:
            if info.mid in self.pub_codes:
                return self.pub_codes[info.mid]
            self.pub_events[info.mid] = ev
        ev.wait(wait)
        with self.lock:
            return self.pub_codes.get(info.mid)

    def telemetry(self, gas: float | None = 50.0, temp: float | None = 29.5, seq: int | None = None,
                  ts: int | None = None, qos: int = 1, wait: bool = True) -> tuple[int, int]:
        if seq is None:
            self.seq += 1
            seq = self.seq
        ts = ts if ts is not None else now_ms()
        body: dict[str, Any] = {"deviceId": self.code, "seq": seq, "ts": ts, "fw": "test-1.0"}
        if gas is not None:
            body["gas"] = {"value": round(gas, 1), "unit": "ppm"}
        if temp is not None:
            body["temp"] = {"value": round(temp, 2), "unit": "C"}
        if wait:
            self.publish(f"{self.base}/telemetry", body, qos=qos)
        else:
            self.client.publish(f"{self.base}/telemetry", json.dumps(body), qos=qos)
        return seq, ts

    def heartbeat(self) -> None:
        self.publish(f"{self.base}/heartbeat", {"deviceId": self.code, "ts": now_ms(), "uptimeSec": 1, "rssi": -50,
                                               "fw": "test-1.0", "state": {"fan": self.fan}}, qos=0)

    def subscribe_raw(self, topic: str, timeout: float = 5) -> list[int]:
        self.suback.clear()
        self.client.subscribe(topic, qos=1)
        self.suback.wait(timeout)
        return self.sub_codes

    def kill(self) -> None:
        """Mất điện đột ngột: đóng TCP không gửi DISCONNECT -> broker phát LWT."""
        self.client.loop_stop()
        sock = self.client.socket()
        if sock is not None:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            sock.close()

    def close(self) -> None:
        try:
            self.client.disconnect()
        except Exception:  # noqa: BLE001
            pass
        self.client.loop_stop()


# ============================================================================ STOMP over WebSocket (giống dashboard)
class StompClient:
    def __init__(self, cfg: Config, token: str) -> None:
        self.messages: list[tuple[float, dict]] = []
        self.errors: list[str] = []
        self.cond = threading.Condition()
        self.ws = websocket.create_connection(cfg.ws, timeout=10)
        self._send("CONNECT", {"accept-version": "1.2", "host": "localhost", "heart-beat": "0,0",
                               "Authorization": f"Bearer {token}"})
        cmd, headers, body = self._read_frame()
        self.connected = cmd == "CONNECTED"
        self.connect_error = None if self.connected else (headers.get("message") or body)
        self._sub = 0
        if self.connected:
            self.ws.settimeout(1)
            self.thread = threading.Thread(target=self._loop, daemon=True)
            self.running = True
            self.thread.start()

    def _send(self, command: str, headers: dict[str, str], body: str = "") -> None:
        self.ws.send(command + "\n" + "".join(f"{k}:{v}\n" for k, v in headers.items()) + "\n" + body + "\x00")

    def _read_frame(self) -> tuple[str, dict, str]:
        buf = ""
        while "\x00" not in buf:
            chunk = self.ws.recv()
            buf += chunk if isinstance(chunk, str) else chunk.decode()
        raw = buf.split("\x00", 1)[0].lstrip("\r\n")
        head, _, body = raw.partition("\n\n")
        lines = head.split("\n")
        headers = dict(l.split(":", 1) for l in lines[1:] if ":" in l)
        return lines[0], headers, body

    def subscribe(self, device_code: str) -> None:
        self._sub += 1
        self._send("SUBSCRIBE", {"id": f"sub-{self._sub}", "destination": f"/topic/devices/{device_code}",
                                 "receipt": f"r-{self._sub}"})
        time.sleep(0.3)

    def _loop(self) -> None:
        buf = ""
        while self.running:
            try:
                chunk = self.ws.recv()
            except websocket.WebSocketTimeoutException:
                continue
            except Exception as e:  # noqa: BLE001
                self.errors.append(str(e))
                return
            t = time.time() * 1000
            buf += chunk if isinstance(chunk, str) else chunk.decode()
            while "\x00" in buf:
                raw, buf = buf.split("\x00", 1)
                raw = raw.lstrip("\r\n")
                if not raw:
                    continue
                head, _, body = raw.partition("\n\n")
                cmd = head.split("\n")[0]
                if cmd == "MESSAGE":
                    try:
                        msg = json.loads(body)
                    except ValueError:
                        continue
                    with self.cond:
                        self.messages.append((t, msg))
                        self.cond.notify_all()
                elif cmd == "ERROR":
                    self.errors.append(raw[:200])

    def wait_for(self, pred: Callable[[dict], bool], timeout: float) -> tuple[float, dict] | None:
        end = time.time() + timeout
        with self.cond:
            while True:
                for t, m in self.messages:
                    if pred(m):
                        return t, m
                left = end - time.time()
                if left <= 0:
                    return None
                self.cond.wait(left)

    def close(self) -> None:
        self.running = False
        try:
            self._send("DISCONNECT", {})
            self.ws.close()
        except Exception:  # noqa: BLE001
            pass


# ============================================================================ báo cáo
class Report:
    """Ghi kết quả kiểm thử ra Markdown (để chèn báo cáo) và JSON (dữ liệu thô)."""

    def __init__(self, kind: str, title: str, cfg: Config) -> None:
        self.kind, self.title, self.cfg = kind, title, cfg
        self.started = dt.datetime.now(VN)
        self.cases: list[dict] = []
        self.sections: list[tuple[str, str]] = []

    def add(self, case: dict) -> None:
        self.cases.append(case)

    def section(self, title: str, markdown: str) -> None:
        self.sections.append((title, markdown))

    def write(self) -> Path:
        REPORT_DIR.mkdir(parents=True, exist_ok=True)
        stamp = self.started.strftime("%Y%m%d-%H%M%S")
        passed = sum(c["status"] == "PASS" for c in self.cases)
        failed = sum(c["status"] == "FAIL" for c in self.cases)
        skipped = sum(c["status"] == "SKIP" for c in self.cases)
        md = [f"# {self.title}", "",
              f"- Thời điểm chạy: **{self.started.strftime('%H:%M:%S %d/%m/%Y')}** (giờ Việt Nam)",
              f"- Thời gian chạy: {(dt.datetime.now(VN) - self.started).total_seconds():.0f} s"]
        md += [f"- {k}: `{v}`" for k, v in self.cfg.describe().items()]
        if self.cases:
            md += [f"- Kết quả: **{passed} đạt / {failed} không đạt / {skipped} bỏ qua** trên {len(self.cases)} ca", "",
                   "| Mã | Ca kiểm thử | Yêu cầu | Kết quả | Thời gian |", "|---|---|---|---|---|"]
            for c in self.cases:
                icon = {"PASS": "✅ Đạt", "FAIL": "❌ Không đạt", "SKIP": "⏭ Bỏ qua"}[c["status"]]
                md.append(f"| {c['id']} | {c['name']} | {c['req']} | {icon} | {c['duration']:.1f} s |")
            md += ["", "## Chi tiết từng ca", ""]
            for c in self.cases:
                md.append(f"### {c['id']} – {c['name']}")
                md.append(f"*Yêu cầu: {c['req']}*  ")
                md.append(f"*Kết quả: {c['status']}*")
                md.append("")
                for step in c["steps"]:
                    md.append(f"- {'✔' if step['ok'] else '✘'} {step['text']}")
                if c.get("error"):
                    md.append(f"- ⚠ Lỗi: `{c['error']}`")
                md.append("")
        for title, body in self.sections:
            md += [f"## {title}", "", body, ""]
        text = "\n".join(md) + "\n"
        path = REPORT_DIR / f"{self.kind}-{stamp}.md"
        path.write_text(text, encoding="utf-8")
        (REPORT_DIR / f"{self.kind}-latest.md").write_text(text, encoding="utf-8")
        data = {"kind": self.kind, "title": self.title, "started": self.started.isoformat(), "env": self.cfg.describe(),
                "cases": self.cases, "sections": [{"title": t, "markdown": b} for t, b in self.sections]}
        (REPORT_DIR / f"{self.kind}-{stamp}.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        return path


class Case:
    """Một ca kiểm thử: ghi từng bước kiểm tra (check) làm bằng chứng; bước sai -> FAIL."""

    def __init__(self, report: Report, cid: str, name: str, req: str) -> None:
        self.report, self.cid, self.name, self.req = report, cid, name, req
        self.steps: list[dict] = []
        self.failed = False
        self.skipped = False

    def check(self, cond: Any, text: str) -> bool:
        ok = bool(cond)
        self.steps.append({"ok": ok, "text": text})
        if not ok:
            self.failed = True
        print(f"   {'✔' if ok else '✘'} {text}", flush=True)
        return ok

    def note(self, text: str) -> None:
        self.steps.append({"ok": True, "text": text})
        print(f"   · {text}", flush=True)

    def skip(self, why: str) -> None:
        self.skipped = True
        self.note("BỎ QUA: " + why)

    def __enter__(self) -> "Case":
        self.t0 = time.time()
        print(f"\n▶ {self.cid} – {self.name}", flush=True)
        return self

    def __exit__(self, et, ev, tb) -> bool:
        err = None
        if ev is not None:
            self.failed = True
            err = f"{et.__name__}: {ev}"
            print(f"   ✘ Lỗi: {err}", flush=True)
        status = "SKIP" if self.skipped and not self.failed else ("FAIL" if self.failed else "PASS")
        self.report.add({"id": self.cid, "name": self.name, "req": self.req, "status": status,
                         "duration": time.time() - self.t0, "steps": self.steps, "error": err})
        print(f"   => {status}", flush=True)
        return True  # không dừng cả bộ test khi 1 ca lỗi


def new_id() -> str:
    return uuid.uuid4().hex[:6]
