#!/usr/bin/env python3
"""
Thiết bị mô phỏng cho hệ thống giám sát rò rỉ khí gas (đóng vai ESP32 + MQ-2 + DS18B20 + quạt).

Dùng để:
  * Kiểm thử backend khi chưa ghép phần cứng thật.
  * Kiểm thử tải: chạy N thiết bị cùng lúc (mục 5.3, 7.2).
  * Tạo tình huống: rò rỉ gas, lỗi cảm biến, mất kết nối đột ngột, bản tin trùng, dữ liệu sai.

Giao thức đúng như thiết kế (topic iot/v1/devices/{deviceId}/...):
  telemetry  (QoS1)  thiết bị -> backend   {"deviceId","seq","ts","gas":{value,unit},"temp":{value,unit}}
  heartbeat  (QoS0)  thiết bị -> backend   {"deviceId","ts","uptimeSec","rssi","fw","state":{fan,fanMode,buzzer,buzzerMode}}
  status     (QoS1, retained, cũng là LWT)  {"deviceId","state":"ONLINE"|"OFFLINE",...}
  event      (QoS1)  thiết bị -> backend   {"deviceId","ts","type":"SENSOR_FAULT"|"SENSOR_OK"|"AUTO_FAN_ON"|"AUTO_FAN_OFF",...}
  cmd        (QoS1)  backend -> thiết bị   {"commandId","action","params"}
                     FAN_SET/BUZZER_SET {on}: giữ BẬT/TẮT (MANUAL); FAN_AUTO/BUZZER_AUTO: về tự động; PING
  cmd/ack    (QoS1)  thiết bị -> backend   {"commandId","result","message","state","ts"}
  config     (QoS1, retained) backend -> thiết bị {"configVersion",...}
  config/ack (QoS1)  thiết bị -> backend   {"configVersion","result","message","applied","ts"}
"""
from __future__ import annotations

import argparse
import json
import os
import random
import sys
import threading
import time
from collections import OrderedDict

try:
    import paho.mqtt.client as mqtt
except ImportError:
    sys.exit("Thiếu thư viện paho-mqtt. Chạy: pip install -r requirements.txt")

TOPIC_ROOT = "iot/v1/devices"
FW_VERSION = "sim-1.0.0"
HW_VERSION = "sim-board"
SUPPORTED_ACTIONS = ("FAN_SET", "FAN_AUTO", "BUZZER_SET", "BUZZER_AUTO", "PING")
FAN_AUTO_OFF_DELAY = 60             # giây: chế độ AUTO tắt quạt sau khi hết báo động
CMD_MEMORY = 20                      # nhớ 20 commandId gần nhất để chống thực thi lặp


def now_ms() -> int:
    return int(time.time() * 1000)


class Log:
    """In log có thời gian + tên thiết bị; an toàn khi nhiều thread cùng in."""
    _lock = threading.Lock()

    def __init__(self, quiet: bool):
        self.quiet = quiet

    def __call__(self, device_id: str, msg: str, force: bool = False) -> None:
        if self.quiet and not force:
            return
        with self._lock:
            print(f"{time.strftime('%H:%M:%S')} [{device_id}] {msg}", flush=True)


class SimDevice:
    def __init__(self, device_id: str, password: str, args: argparse.Namespace, log: Log):
        self.id = device_id
        self.args = args
        self.log = log
        self.base = f"{TOPIC_ROOT}/{device_id}"
        self.started = time.time()
        self.seq = 0
        self.fan = False
        self.fan_manual = False          # True = người dùng giữ bật/tắt (FAN_SET), False = tự động theo báo động
        self.fan_manual_on = False
        self.buzzer_manual = False
        self.buzzer_manual_on = False
        self.buzzer = False              # còi đang kêu
        self.alarm = False
        self.alarm_cleared_at = None
        self.temp_fault = False
        self.stop = threading.Event()
        self.config = {
            "configVersion": 0,
            "telemetryIntervalSec": args.interval,
            "gasThreshold": args.gas_threshold,
            "autoFan": True,
        }
        self.recent_cmds: OrderedDict[str, dict] = OrderedDict()
        self.stats = {"telemetry": 0, "skipped_offline": 0, "duplicates_sent": 0, "heartbeat": 0,
                      "cmd": 0, "cmd_duplicate": 0, "config": 0, "connects": 0}

        c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=device_id,
                        protocol=mqtt.MQTTv311, clean_session=True)
        c.username_pw_set(device_id, password)
        # LWT: broker tự phát bản tin này nếu thiết bị mất kết nối mà không kịp báo
        c.will_set(f"{self.base}/status",
                   json.dumps({"deviceId": device_id, "state": "OFFLINE", "reason": "LWT"}),
                   qos=1, retain=True)
        c.reconnect_delay_set(min_delay=1, max_delay=30)   # tự kết nối lại, giãn dần tới 30 s
        c.on_connect = self._on_connect
        c.on_disconnect = self._on_disconnect
        c.on_subscribe = self._on_subscribe
        c.on_message = self._on_message
        self.client = c

    # ------------------------------------------------------------------ MQTT callbacks
    def _on_connect(self, client, userdata, flags, reason_code, properties):
        if reason_code.is_failure:
            self.log(self.id, f"Broker TỪ CHỐI kết nối: {reason_code} (sai mật khẩu hoặc thiết bị bị khóa?)",
                     force=True)
            return
        self.stats["connects"] += 1
        self.log(self.id, "Đã kết nối broker", force=True)
        self._publish("status", {"deviceId": self.id, "state": "ONLINE", "ts": now_ms(),
                                 "fw": FW_VERSION, "hw": HW_VERSION}, qos=1, retain=True)
        client.subscribe([(f"{self.base}/cmd", 1), (f"{self.base}/config", 1)])

    def _on_disconnect(self, client, userdata, flags, reason_code, properties):
        if not self.stop.is_set():
            self.log(self.id, f"Mất kết nối ({reason_code}). Đang tự kết nối lại...", force=True)

    def _on_subscribe(self, client, userdata, mid, reason_codes, properties):
        for rc in reason_codes:
            if rc.is_failure:
                self.log(self.id, f"Broker từ chối subscribe: {rc}", force=True)

    def _on_message(self, client, userdata, msg):
        try:
            if msg.topic.endswith("/cmd"):
                self._handle_command(msg.payload)
            elif msg.topic.endswith("/config"):
                self._handle_config(msg.payload)
        except Exception as e:                       # không để 1 bản tin lỗi làm chết thiết bị
            self.log(self.id, f"Lỗi xử lý bản tin {msg.topic}: {e}", force=True)

    # ------------------------------------------------------------------ Lệnh điều khiển
    def _handle_command(self, payload: bytes) -> None:
        try:
            cmd = json.loads(payload)
        except ValueError:
            self.log(self.id, "Bỏ qua lệnh không phải JSON")
            return
        cid = cmd.get("commandId")
        action = cmd.get("action")
        params = cmd.get("params") or {}
        if not isinstance(cid, str) or not cid:
            self.log(self.id, "Bỏ qua lệnh thiếu commandId")
            return

        # QoS1 có thể giao lại cùng 1 lệnh -> không thực thi lần 2, chỉ gửi lại ACK cũ
        if cid in self.recent_cmds:
            self.stats["cmd_duplicate"] += 1
            self.log(self.id, f"Lệnh {cid[:8]} bị gửi trùng -> không thực thi lại, gửi lại ACK cũ")
            self._publish("cmd/ack", self.recent_cmds[cid], qos=1)
            return

        self.stats["cmd"] += 1
        on = params.get("on") if isinstance(params, dict) else None
        if action in ("FAN_SET", "BUZZER_SET"):
            if isinstance(on, bool):
                if action == "FAN_SET":
                    self.fan_manual, self.fan_manual_on = True, on
                    message = "Quạt BẬT (giữ bật đến khi đổi lại)" if on else "Quạt TẮT (giữ tắt, kể cả khi có báo động)"
                else:
                    self.buzzer_manual, self.buzzer_manual_on = True, on
                    message = "Còi BẬT (kêu đến khi tắt)" if on else "Còi TẮT (im lặng, kể cả khi có báo động)"
                self._apply_actuators(None)
                result = "SUCCESS"
            else:
                result, message = "FAILED", "params.on phải là true hoặc false"
        elif action in ("FAN_AUTO", "BUZZER_AUTO"):
            if action == "FAN_AUTO":
                self.fan_manual = False
            else:
                self.buzzer_manual = False
            self._apply_actuators(None)
            result, message = "SUCCESS", "Quạt về TỰ ĐỘNG" if action == "FAN_AUTO" else "Còi về TỰ ĐỘNG"
        elif action == "PING":
            result, message = "SUCCESS", "pong"
        else:
            result, message = "FAILED", f"Không hỗ trợ lệnh '{action}'"

        self.log(self.id, f"Nhận lệnh {action} ({cid[:8]}) -> {result}: {message}", force=True)
        if self.args.no_ack:
            self.log(self.id, "--no-ack: KHÔNG gửi ACK (mô phỏng thiết bị không phản hồi -> TIMEOUT)", force=True)
            return
        if self.args.ack_delay > 0:
            time.sleep(self.args.ack_delay)

        ack = {"commandId": cid, "result": result, "message": message,
               "state": self._state(), "ts": now_ms()}
        self.recent_cmds[cid] = ack
        while len(self.recent_cmds) > CMD_MEMORY:
            self.recent_cmds.popitem(last=False)
        self._publish("cmd/ack", ack, qos=1)

    # ------------------------------------------------------------------ Cấu hình từ xa
    def _handle_config(self, payload: bytes) -> None:
        if not payload:                              # backend xóa retained config (decommission)
            return
        try:
            cfg = json.loads(payload)
        except ValueError:
            self.log(self.id, "Bỏ qua cấu hình không phải JSON")
            return
        version = cfg.get("configVersion")
        errors = []
        if not isinstance(version, int) or isinstance(version, bool) or version < 1:
            errors.append("configVersion phải là số nguyên >= 1")
        if "telemetryIntervalSec" in cfg:
            v = cfg["telemetryIntervalSec"]
            if not isinstance(v, int) or isinstance(v, bool) or not 1 <= v <= 3600:
                errors.append("telemetryIntervalSec phải từ 1 đến 3600")
        if "gasThreshold" in cfg:
            v = cfg["gasThreshold"]
            if not isinstance(v, (int, float)) or isinstance(v, bool) or not 50 <= v <= 10000:
                errors.append("gasThreshold phải từ 50 đến 10000 ppm")
        if "autoFan" in cfg and not isinstance(cfg["autoFan"], bool):
            errors.append("autoFan phải là true/false")

        if errors:
            ack = {"configVersion": version, "result": "REJECTED", "message": "; ".join(errors),
                   "applied": dict(self.config), "ts": now_ms()}
            self.log(self.id, f"TỪ CHỐI cấu hình v{version}: {ack['message']}", force=True)
        else:
            # Retained config được giao lại mỗi lần kết nối lại -> áp dụng lại là vô hại (idempotent)
            for key in ("telemetryIntervalSec", "gasThreshold", "autoFan"):
                if key in cfg:
                    self.config[key] = cfg[key]
            self.config["configVersion"] = version
            self.stats["config"] += 1
            ack = {"configVersion": version, "result": "APPLIED", "message": "OK",
                   "applied": dict(self.config), "ts": now_ms()}
            self.log(self.id, f"Áp dụng cấu hình v{version}: {self.config}", force=True)
        self._publish("config/ack", ack, qos=1)

    # ------------------------------------------------------------------ Cảm biến mô phỏng
    def _read_sensors(self) -> tuple[float, float | None]:
        elapsed = time.time() - self.started
        gas = random.gauss(180, 15)                  # không khí bình thường ~180 ppm
        if self.args.scenario == "leak" and elapsed >= self.args.leak_after:
            gas += min(1400.0, (elapsed - self.args.leak_after) * 60)   # tăng ~60 ppm/s tới +1400
        if self.fan:
            gas *= 0.6                               # quạt hút làm giảm nồng độ
        gas = round(max(0.0, gas), 1)

        if self.args.scenario == "fault" and elapsed >= self.args.fault_after:
            recovered = self.args.fault_duration > 0 and elapsed >= self.args.fault_after + self.args.fault_duration
            if not recovered and not self.temp_fault:
                self.temp_fault = True
                self._event("SENSOR_FAULT", sensor="temp", message="DS18B20 không phản hồi (đọc được -127)")
            elif recovered and self.temp_fault:
                self.temp_fault = False
                self._event("SENSOR_OK", sensor="temp", message="DS18B20 hoạt động lại")
        temp = None if self.temp_fault else round(random.gauss(29.5, 0.3), 2)
        return gas, temp

    def _state(self) -> dict:
        return {"fan": self.fan, "fanMode": "MANUAL" if self.fan_manual else "AUTO",
                "buzzer": self.buzzer, "buzzerMode": "MANUAL" if self.buzzer_manual else "AUTO"}

    def _edge_rule(self, gas: float) -> None:
        """Luật tại thiết bị: gas vượt ngưỡng -> báo động; quạt/còi theo chế độ AUTO/MANUAL (giống firmware)."""
        was = self.alarm
        if gas > self.config["gasThreshold"]:
            self.alarm = True
        elif gas < self.config["gasThreshold"] * 0.9:
            self.alarm = False
        if was and not self.alarm:
            self.alarm_cleared_at = time.time()
        if self.alarm:
            self.alarm_cleared_at = None
        self._apply_actuators(gas)

    def _apply_actuators(self, gas) -> None:
        if self.fan_manual:
            self.fan = self.fan_manual_on
        else:
            keep = self.alarm or (self.alarm_cleared_at is not None
                                  and time.time() - self.alarm_cleared_at < FAN_AUTO_OFF_DELAY)
            want = self.config["autoFan"] and keep
            if want and not self.fan:
                self.fan = True
                self.log(self.id, f"Gas {gas} ppm > ngưỡng {self.config['gasThreshold']} -> TỰ BẬT QUẠT", force=True)
                self._event("AUTO_FAN_ON", reason="GAS", gas=gas, threshold=self.config["gasThreshold"])
            elif not want and self.fan:
                self.fan = False
                self.log(self.id, "Hết báo động -> TỰ TẮT QUẠT", force=True)
                self._event("AUTO_FAN_OFF", reason="CLEARED")
        self.buzzer = self.buzzer_manual_on if self.buzzer_manual else self.alarm

    # ------------------------------------------------------------------ Gửi bản tin
    def _publish(self, suffix: str, body: dict, qos: int, retain: bool = False) -> bool:
        info = self.client.publish(f"{self.base}/{suffix}", json.dumps(body), qos=qos, retain=retain)
        return info.rc == mqtt.MQTT_ERR_SUCCESS

    def _event(self, event_type: str, **fields) -> None:
        self._publish("event", {"deviceId": self.id, "ts": now_ms(), "type": event_type, **fields}, qos=1)

    def _send_telemetry(self) -> None:
        gas, temp = self._read_sensors()
        self._edge_rule(gas)
        self.seq += 1
        body = {"deviceId": self.id, "seq": self.seq, "ts": now_ms(), "gas": {"value": gas, "unit": "ppm"}}
        if temp is not None:
            body["temp"] = {"value": temp, "unit": "C"}
        if self._publish("telemetry", body, qos=1):
            self.stats["telemetry"] += 1
            self.log(self.id, f"telemetry seq={self.seq} gas={gas}ppm temp={temp}C fan={self.fan}")
            if self.args.duplicate_rate > 0 and random.random() < self.args.duplicate_rate:
                self._publish("telemetry", body, qos=1)          # cùng ts -> backend phải loại trùng
                self.stats["duplicates_sent"] += 1
                self.log(self.id, f"  (gửi TRÙNG seq={self.seq} để kiểm thử chống trùng)")
        else:
            self.stats["skipped_offline"] += 1

    def _send_heartbeat(self) -> None:
        body = {"deviceId": self.id, "ts": now_ms(), "uptimeSec": int(time.time() - self.started),
                "rssi": random.randint(-70, -45), "fw": FW_VERSION, "state": self._state()}
        if self._publish("heartbeat", body, qos=0):
            self.stats["heartbeat"] += 1

    # ------------------------------------------------------------------ Vòng đời
    def start(self) -> None:
        self.client.connect_async(self.args.host, self.args.port, keepalive=self.args.keepalive)
        self.client.loop_start()
        threading.Thread(target=self._loop, name=self.id, daemon=True).start()

    def _loop(self) -> None:
        next_tel = next_hb = time.time() + random.uniform(0, 1)  # lệch pha giữa các thiết bị
        while not self.stop.is_set():
            now = time.time()
            if self.client.is_connected():
                if now >= next_tel:
                    self._send_telemetry()
                    next_tel = now + self.config["telemetryIntervalSec"]
                if now >= next_hb:
                    self._send_heartbeat()
                    next_hb = now + self.args.heartbeat
            elif now >= next_tel:
                self.stats["skipped_offline"] += 1        # đến lượt gửi nhưng đang offline
                next_tel = now + self.config["telemetryIntervalSec"]
            self.stop.wait(0.1)

    def shutdown(self) -> None:
        """Tắt có kiểm soát: tự báo OFFLINE rồi ngắt (broker KHÔNG phát LWT trong trường hợp này)."""
        self.stop.set()
        if self.client.is_connected():
            info = self.client.publish(f"{self.base}/status",
                                       json.dumps({"deviceId": self.id, "state": "OFFLINE",
                                                   "reason": "SHUTDOWN", "ts": now_ms()}),
                                       qos=1, retain=True)
            try:
                info.wait_for_publish(timeout=2)
            except (RuntimeError, ValueError):
                pass
            self.client.disconnect()
        self.client.loop_stop()


# ---------------------------------------------------------------------- Gửi dữ liệu sai (kiểm thử bảo mật)
def send_invalid(device: SimDevice, log: Log) -> None:
    """Gửi các bản tin sai để chứng minh backend từ chối dữ liệu không hợp lệ (mục 4.5)."""
    t = f"{device.base}/telemetry"
    good_ts = now_ms()
    cases = [
        ("Không phải JSON", "day-khong-phai-json"),
        ("deviceId không khớp topic", json.dumps({"deviceId": "esp32-khac", "seq": 1, "ts": good_ts,
                                                   "gas": {"value": 200, "unit": "ppm"}})),
        ("Thiếu timestamp", json.dumps({"deviceId": device.id, "seq": 2, "gas": {"value": 200, "unit": "ppm"}})),
        ("Timestamp năm 1970 (chưa đồng bộ NTP)", json.dumps({"deviceId": device.id, "seq": 3, "ts": 5000,
                                                             "gas": {"value": 200, "unit": "ppm"}})),
        ("Gas âm", json.dumps({"deviceId": device.id, "seq": 4, "ts": good_ts + 1,
                               "gas": {"value": -50, "unit": "ppm"}})),
        ("Nhiệt độ ngoài dải DS18B20 (-55..125)", json.dumps({"deviceId": device.id, "seq": 5, "ts": good_ts + 2,
                                                             "temp": {"value": 999, "unit": "C"}})),
        ("Sai đơn vị", json.dumps({"deviceId": device.id, "seq": 6, "ts": good_ts + 3,
                                   "gas": {"value": 200, "unit": "kg"}})),
        ("Chỉ số lạ", json.dumps({"deviceId": device.id, "seq": 7, "ts": good_ts + 4,
                                  "humidity": {"value": 50, "unit": "%"}})),
        ("Payload quá lớn (>4 KB)", json.dumps({"deviceId": device.id, "seq": 8, "ts": good_ts + 5,
                                               "gas": {"value": 200, "unit": "ppm"}, "junk": "x" * 5000})),
    ]
    for name, payload in cases:
        device.client.publish(t, payload, qos=1).wait_for_publish(timeout=3)
        log(device.id, f"Đã gửi bản tin SAI: {name}", force=True)
        time.sleep(0.3)
    log(device.id, "Xong. Backend phải từ chối cả 9 bản tin trên và KHÔNG lưu vào bảng telemetry.", force=True)


# ---------------------------------------------------------------------- Main
def load_devices(args: argparse.Namespace) -> list[tuple[str, str]]:
    if args.devices_file:
        with open(args.devices_file, encoding="utf-8") as f:
            items = json.load(f)
        devices = [(d["deviceId"], d["password"]) for d in items]
        return devices[: args.count] if args.count else devices
    password = args.password or os.environ.get("SIM_PASSWORD")
    if not args.device or not password:
        sys.exit("Cần --device và --password (hoặc biến môi trường SIM_PASSWORD), hoặc --devices-file")
    return [(args.device, password)]


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Thiết bị mô phỏng gas node (MQTT)",
                                formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    p.add_argument("--host", default="localhost", help="Địa chỉ broker MQTT")
    p.add_argument("--port", type=int, default=1883)
    p.add_argument("--device", help="Mã thiết bị (cũng là username và clientId MQTT)")
    p.add_argument("--password", help="Mật khẩu MQTT của thiết bị")
    p.add_argument("--devices-file", help="File JSON [{deviceId,password}] để chạy nhiều thiết bị")
    p.add_argument("--count", type=int, default=0, help="Chỉ chạy N thiết bị đầu trong devices-file")
    p.add_argument("--interval", type=int, default=5, help="Chu kỳ gửi telemetry (giây)")
    p.add_argument("--heartbeat", type=int, default=10, help="Chu kỳ heartbeat (giây)")
    p.add_argument("--keepalive", type=int, default=15, help="MQTT keepalive (giây); LWT sau ~1.5x")
    p.add_argument("--gas-threshold", type=float, default=400, help="Ngưỡng gas mặc định cho luật tự bật quạt")
    p.add_argument("--scenario", choices=["normal", "leak", "fault"], default="normal")
    p.add_argument("--leak-after", type=float, default=20, help="leak: bắt đầu rò rỉ sau N giây")
    p.add_argument("--fault-after", type=float, default=20, help="fault: cảm biến nhiệt lỗi sau N giây")
    p.add_argument("--fault-duration", type=float, default=30, help="fault: tự hồi phục sau N giây (0 = không)")
    p.add_argument("--duplicate-rate", type=float, default=0.0, help="Tỉ lệ gửi trùng telemetry (0..1)")
    p.add_argument("--ack-delay", type=float, default=0.0, help="Trễ trước khi gửi ACK lệnh (giây)")
    p.add_argument("--no-ack", action="store_true", help="Không gửi ACK lệnh (kiểm thử TIMEOUT)")
    p.add_argument("--kill-after", type=float, default=0,
                   help="Sau N giây thì 'mất điện' đột ngột (không báo OFFLINE) -> broker phát LWT")
    p.add_argument("--duration", type=float, default=0, help="Tự dừng sau N giây (0 = chạy đến khi Ctrl+C)")
    p.add_argument("--send-invalid", action="store_true", help="Gửi 9 loại bản tin sai rồi thoát")
    p.add_argument("--quiet", action="store_true", help="Chỉ in sự kiện quan trọng (dùng khi chạy nhiều thiết bị)")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    log = Log(args.quiet)
    devices = [SimDevice(d, pw, args, log) for d, pw in load_devices(args)]

    if args.send_invalid:
        dev = devices[0]
        dev.client.connect(args.host, args.port, keepalive=args.keepalive)
        dev.client.loop_start()
        time.sleep(1)
        if not dev.client.is_connected():
            sys.exit("Không kết nối được broker (kiểm tra mật khẩu/thiết bị có bị khóa không)")
        send_invalid(dev, log)
        dev.shutdown()
        return

    print(f"Khởi động {len(devices)} thiết bị -> {args.host}:{args.port} | kịch bản={args.scenario} "
          f"| telemetry mỗi {args.interval}s | heartbeat mỗi {args.heartbeat}s. Ctrl+C để dừng.", flush=True)
    for d in devices:
        d.start()

    started = time.time()
    last_report = started
    try:
        while True:
            time.sleep(0.5)
            elapsed = time.time() - started
            if args.kill_after and elapsed >= args.kill_after:
                print(f"\n*** MÔ PHỎNG MẤT ĐIỆN sau {args.kill_after:.0f}s: thoát ngay, không gửi OFFLINE. "
                      "Broker sẽ phát LWT 'OFFLINE'. ***", flush=True)
                os._exit(2)
            if args.duration and elapsed >= args.duration:
                break
            if args.quiet and time.time() - last_report >= 10:
                last_report = time.time()
                total = sum(d.stats["telemetry"] for d in devices)
                online = sum(1 for d in devices if d.client.is_connected())
                print(f"{time.strftime('%H:%M:%S')} online {online}/{len(devices)} | telemetry đã gửi: {total}",
                      flush=True)
    except KeyboardInterrupt:
        pass

    print("\nĐang dừng (gửi status OFFLINE)...", flush=True)
    for d in devices:
        d.shutdown()
    print("\n=== THỐNG KÊ ===")
    for d in devices:
        s = d.stats
        print(f"{d.id}: telemetry={s['telemetry']} (seq cuối={d.seq}), gửi trùng={s['duplicates_sent']}, "
              f"bỏ lỡ khi offline={s['skipped_offline']}, heartbeat={s['heartbeat']}, lệnh={s['cmd']} "
              f"(trùng {s['cmd_duplicate']}), cấu hình={s['config']}, số lần kết nối={s['connects']}")


if __name__ == "__main__":
    main()
