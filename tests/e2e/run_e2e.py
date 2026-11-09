"""
Kiểm thử chức năng (mục 7.1) và kiểm thử bảo mật (mục 4.5) – tự động, đầu-cuối.

Chạy trên hệ thống THẬT đang hoạt động (backend + Mosquitto + PostgreSQL, vd. `app-up`):
    python tests/e2e/run_e2e.py

Chức năng F1–F9, bảo mật S1–S9 (S9: kết nối mã hóa TLS – MQTT 8883 và web HTTPS).
Mỗi lần chạy tự tạo dữ liệu riêng (người dùng e2e_*_<mã>, thiết bị e2e-<mã>), thiết bị được
mô phỏng bằng MQTT đúng giao thức của firmware ESP32; cuối cùng khóa người dùng test và ngừng
sử dụng thiết bị test. Kết quả ghi ra tests/reports/e2e-<thời điểm>.md (+ .json) làm bằng chứng.
"""
from __future__ import annotations

import json
import os
import secrets
import shlex
import socket
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "common"))
from gasguard_test import (REASON_NOT_AUTHORIZED, Api, Case, Config, Report, SimDevice, StompClient,  # noqa: E402
                           forge_expired_token, iso, now_ms, run_id, wait_until)

DENIED = {134, 135}  # MQTT v5: 0x86 Bad user name or password, 0x87 Not authorized

cfg = Config()
api = Api(cfg)
report = Report("e2e", "Báo cáo kiểm thử chức năng (7.1) và bảo mật (4.5) – GasGuard", cfg)
RID = run_id()
PW = lambda: "T" + secrets.token_hex(6) + "9"  # noqa: E731  mật khẩu ngẫu nhiên đủ chính sách
S: dict = {}  # trạng thái dùng chung giữa các ca


def q(params: dict) -> str:
    return "?" + urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})


def device(code: str, tok: str) -> dict:
    return api.call("GET", f"/api/devices/{code}", token=tok)[1]


def alerts(code: str, tok: str, status: str = "ACTIVE") -> list[dict]:
    return api.call("GET", "/api/alerts" + q({"deviceCode": code, "status": status, "size": 100}), token=tok)[1]["items"]


def ingest_stats() -> dict:
    return api.call("GET", "/api/system/ingest-stats", token=S["admin"])[1]


def db_query(sql: str) -> str | None:
    """Truy vấn PostgreSQL: qua container Docker iot-postgres, hoặc lệnh trong GG_PSQL (môi trường dev)."""
    cmd = os.environ.get("GG_PSQL")
    if cmd:
        args = shlex.split(cmd) + [sql]
    else:
        env = {}
        envf = Path(__file__).resolve().parents[2] / ".env"
        if envf.exists():
            for line in envf.read_text(encoding="utf-8").splitlines():
                if "=" in line and not line.startswith("#"):
                    k, v = line.split("=", 1)
                    env[k.strip()] = v.strip()
        args = ["docker", "exec", "iot-postgres", "psql", "-U", env.get("POSTGRES_USER", "iot"),
                "-d", env.get("POSTGRES_DB", "iotdb"), "-tAc", sql]
    try:
        r = subprocess.run(args, capture_output=True, text=True, timeout=20)
        return r.stdout.strip() if r.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        return None


# ============================================================================ chuẩn bị
def setup() -> None:
    print(f"Mã lần chạy: {RID}  |  API {cfg.api}  |  MQTT {cfg.mqtt_host}:{cfg.mqtt_port}")
    s, b = api.call("GET", "/actuator/health")
    if s != 200:
        raise SystemExit(f"Backend chưa chạy ({cfg.api}/actuator/health -> {s}). Hãy chạy hệ thống trước.")
    S["admin"] = api.login(cfg.admin_user, cfg.admin_password)
    for key, role in [("owner", "OWNER"), ("viewer", "VIEWER"), ("other", "OWNER"), ("lock", "VIEWER")]:
        name, pw = f"e2e_{key}_{RID}", PW()
        st, body = api.call("POST", "/api/admin/users",
                            {"username": name, "password": pw, "role": role, "fullName": f"E2E {key}"}, S["admin"])
        if st not in (200, 201):
            raise SystemExit(f"Không tạo được người dùng test {name}: {st} {body}")
        S[f"{key}_name"], S[f"{key}_pw"], S[f"{key}_id"] = name, pw, body["id"]
        S[key] = api.login(name, pw) if key != "lock" else None
    S["code"], S["code_b"] = f"e2e-{RID}", f"e2e-{RID}-b"


def provision(code: str, tok: str, name: str) -> str:
    st, b = api.call("POST", "/api/devices", {"deviceCode": code, "name": name, "deviceType": "GAS_NODE",
                                              "location": "Phòng test", "zone": "E2E"}, tok)
    assert st in (200, 201), f"đăng ký {code}: {st} {b}"
    st, p = api.call("POST", f"/api/devices/{code}/provision", token=tok)
    assert st in (200, 201), f"cấp xác thực {code}: {st} {p}"
    return p["password"]


# ============================================================================ 7.1 – chức năng
def f1_login_roles() -> None:
    with Case(report, "F1", "Đăng nhập và phân quyền theo vai trò", "7.1 · 4.4a · 8.4-1") as c:
        for key, role in [("owner", "OWNER"), ("viewer", "VIEWER")]:
            st, me = api.call("GET", "/api/auth/me", token=S[key])
            c.check(st == 200 and me["role"] == role, f"{S[key + '_name']} đăng nhập được, /me trả vai trò {me.get('role')}")
        st, me = api.call("GET", "/api/auth/me", token=S["admin"])
        c.check(st == 200 and me["role"] == "ADMIN", "admin đăng nhập được, vai trò ADMIN")
        st, _ = api.call("POST", "/api/auth/login", {"username": S["viewer_name"], "password": "SaiMatKhau123"})
        c.check(st == 401, f"Sai mật khẩu -> HTTP {st} (mong đợi 401)")
        st_v = api.call("GET", "/api/admin/users", token=S["viewer"])[0]
        st_o = api.call("GET", "/api/admin/users", token=S["owner"])[0]
        st_a = api.call("GET", "/api/admin/users", token=S["admin"])[0]
        c.check(st_v == 403 and st_o == 403 and st_a == 200,
                f"Quản lý người dùng: VIEWER {st_v}, OWNER {st_o}, ADMIN {st_a} (mong đợi 403/403/200)")
        st = api.call("POST", "/api/devices", {"deviceCode": f"e2e-{RID}-v", "name": "x"}, S["viewer"])[0]
        c.check(st == 403, f"VIEWER đăng ký thiết bị -> HTTP {st} (mong đợi 403)")
        name = f"e2e_reg_{RID}"
        st, b = api.call("POST", "/api/auth/register", {"username": name, "password": PW()})
        c.check(st == 201 and b["role"] == "VIEWER", f"Tự đăng ký tài khoản -> HTTP {st}, vai trò mặc định {b.get('role')}")
        S["reg_id"] = b.get("id")
        tmp = api.login(S["viewer_name"], S["viewer_pw"])
        api.call("POST", "/api/auth/logout", token=tmp)
        st = api.call("GET", "/api/auth/me", token=tmp)[0]
        c.check(st == 401, f"Sau đăng xuất, token cũ -> HTTP {st} (mong đợi 401)")
        S["viewer"] = api.login(S["viewer_name"], S["viewer_pw"])


def f2_register_activate() -> None:
    with Case(report, "F2", "Đăng ký, cấp xác thực và kích hoạt thiết bị", "7.1 · 4.4b · 8.4-2") as c:
        code, tok = S["code"], S["owner"]
        st, d = api.call("POST", "/api/devices", {"deviceCode": code, "name": "E2E bếp", "deviceType": "GAS_NODE",
                                                  "location": "Phòng test", "zone": "E2E"}, tok)
        c.check(st == 201 and d["lifecycleStatus"] == "REGISTERED", f"Đăng ký {code} -> HTTP {st}, trạng thái {d.get('lifecycleStatus')}")
        st = api.call("POST", "/api/devices", {"deviceCode": code, "name": "trùng"}, tok)[0]
        c.check(st == 409, f"Đăng ký trùng mã -> HTTP {st} (mong đợi 409)")
        st, p = api.call("POST", f"/api/devices/{code}/provision", token=tok)
        c.check(st in (200, 201) and len(p.get("password", "")) >= 16 and p["username"] == code and p["clientId"] == code,
                f"Cấp thông tin xác thực riêng -> username = clientId = {p.get('username')}, mật khẩu {len(p.get('password', ''))} ký tự")
        S["pw"] = p["password"]
        c.check(device(code, tok)["lifecycleStatus"] == "PROVISIONED", "Trạng thái PROVISIONED sau khi cấp")
        st, cred = api.call("GET", f"/api/devices/{code}/credentials", token=tok)
        c.check(st == 200 and "password" not in json.dumps(cred).lower().replace("passwordhash", ""),
                "Xem lại thông tin xác thực KHÔNG trả mật khẩu (chỉ hiện 1 lần)")
        dev = SimDevice(cfg, code, S["pw"])
        rc = dev.connect()
        c.check(rc == 0, f"Thiết bị kết nối broker bằng thông tin riêng -> CONNACK {rc}")
        dev.telemetry(gas=40, temp=28.0)
        d = wait_until(lambda: (lambda x: x if x["lifecycleStatus"] == "ACTIVE" else None)(device(code, tok)), 8)
        c.check(d and d["connectivity"] == "ONLINE", f"Bản tin đầu tiên -> tự kích hoạt ACTIVE, kết nối {d and d['connectivity']}")
        S["dev"] = dev
        # thiết bị B của người khác (dùng cho kiểm thử bảo mật)
        S["pw_b"] = provision(S["code_b"], S["other"], "E2E thiết bị người khác")
        S["dev_b"] = SimDevice(cfg, S["code_b"], S["pw_b"])
        c.check(S["dev_b"].connect() == 0, f"Thiết bị thứ 2 ({S['code_b']}, chủ khác) kết nối bằng mật khẩu riêng của nó")


def f3_telemetry() -> None:
    with Case(report, "F3", "Thiết bị gửi telemetry, backend kiểm tra, lưu và truy vấn", "7.1 · 4.4c · 8.4-3") as c:
        code, tok, dev = S["code"], S["owner"], S["dev"]
        t0 = time.time() - 2
        values = [(55.0 + i, 29.0 + i / 10) for i in range(10)]
        for g, t in values:
            dev.telemetry(gas=g, temp=t)
            time.sleep(0.05)
        rng = {"from": iso(t0), "to": iso(time.time() + 5)}
        page = wait_until(lambda: (lambda b: b if b["count"] >= 20 else None)(
            api.call("GET", f"/api/devices/{code}/telemetry" + q({**rng, "metric": "all", "limit": 100}), token=tok)[1]), 6)
        c.check(page and page["count"] >= 20, f"Lưu đủ 10 bản tin x 2 chỉ số = {page and page['count']} điểm")
        gas = sorted(p["value"] for p in (page or {"points": []})["points"] if p["metric"] == "gas")[-10:]
        c.check(gas == [g for g, _ in values], "Giá trị gas lưu đúng như thiết bị gửi")
        units = {p["metric"]: p["unit"] for p in (page or {"points": []})["points"]}
        c.check(units.get("gas") == "ppm" and units.get("temp") == "C", f"Đơn vị chuẩn hóa: {units}")
        c.check(all(p.get("seq") for p in page["points"]), "Mỗi điểm có seq (phát hiện mất bản tin)")
        latest = api.call("GET", f"/api/devices/{code}/telemetry/latest", token=tok)[1]
        c.check(latest["gas"]["value"] == values[-1][0], f"Giá trị hiện tại (latest) gas = {latest['gas']['value']}")
        st, stats = api.call("GET", f"/api/devices/{code}/telemetry/stats" + q(rng), token=tok)
        gstat = next((m for m in stats.get("metrics", []) if m["metric"] == "gas"), {})
        c.check(st == 200 and gstat.get("count", 0) >= 10 and gstat.get("max") == values[-1][0],
                f"Thống kê: count {gstat.get('count')}, min {gstat.get('min')}, max {gstat.get('max')}, avg {gstat.get('avg')}")
        st, series = api.call("GET", f"/api/devices/{code}/telemetry/series" + q({**rng, "metric": "gas"}), token=tok)
        c.check(st == 200, f"Dữ liệu biểu đồ (series) -> HTTP {st}")
        st, csv = api.call("GET", f"/api/devices/{code}/telemetry/export.csv" + q(rng), token=tok)
        c.check(st == 200 and isinstance(csv, str) and csv.count("\n") >= 20, f"Xuất CSV -> HTTP {st}, {isinstance(csv, str) and csv.count(chr(10))} dòng")


def f4_dashboard_realtime() -> None:
    with Case(report, "F4", "Dashboard cập nhật dữ liệu thời gian thực (WebSocket)", "7.1 · 4.4d · 8.4-4") as c:
        code, dev = S["code"], S["dev"]
        ws = StompClient(cfg, S["owner"])
        c.check(ws.connected, "Dashboard (STOMP/WebSocket) kết nối bằng JWT")
        ws.subscribe(code)
        marker = 77.7
        t_pub = time.time() * 1000
        dev.telemetry(gas=marker, temp=30.1)
        hit = ws.wait_for(lambda m: m.get("type") == "telemetry" and m.get("readings", {}).get("gas", {}).get("value") == marker, 5)
        c.check(hit is not None, "Bản tin vừa gửi xuất hiện trên kênh realtime của dashboard")
        if hit:
            c.note(f"Độ trễ thiết bị publish -> dashboard nhận: {hit[0] - t_pub:.0f} ms")
            S["ws_latency"] = hit[0] - t_pub
        st, dash = api.call("GET", "/api/dashboard", token=S["owner"])
        card = next((d for d in dash.get("devices", []) if d["deviceCode"] == code), None)
        c.check(card and card["connectivity"] == "ONLINE" and card["latest"]["gas"]["value"] == marker,
                f"API dashboard: {code} trực tuyến, gas hiện tại {card and card['latest']['gas']['value']}")
        S["ws_owner"] = ws


def f5_commands() -> None:
    with Case(report, "F5", "Gửi lệnh điều khiển và nhận xác nhận (ACK)", "7.1 · 4.4e · 8.4-5") as c:
        code, tok, dev = S["code"], S["owner"], S["dev"]
        st, cmd = api.call("POST", f"/api/devices/{code}/commands?waitMs=5000", {"action": "FAN_SET", "params": {"on": True}}, tok)
        c.check(st == 201 and cmd["status"] == "SUCCESS", f"FAN_SET bật -> HTTP {st}, trạng thái {cmd.get('status')}, độ trễ {cmd.get('latencyMs')} ms")
        c.check(any(x.get("commandId") == cmd.get("id") for x in dev.cmds), "Thiết bị nhận đúng lệnh (commandId khớp)")
        c.check((cmd.get("resultState") or {}).get("fan") is True, f"ACK kèm trạng thái mới: {cmd.get('resultState')}")
        c.check(device(code, tok)["actuatorState"].get("fan") is True, "Backend cập nhật trạng thái quạt = BẬT")
        st, cmd2 = api.call("POST", f"/api/devices/{code}/commands?waitMs=5000", {"action": "PING", "params": {}}, tok)
        c.check(cmd2.get("status") == "SUCCESS", f"PING -> {cmd2.get('status')} ({cmd2.get('latencyMs')} ms)")
        st, hist = api.call("GET", f"/api/devices/{code}/commands?limit=10", token=tok)
        ids = {h["id"] for h in hist}
        c.check(cmd["id"] in ids and cmd2["id"] in ids, f"Lịch sử lệnh lưu cả 2 lệnh (người gửi {hist[0].get('requestedBy')})")
        st = api.call("POST", f"/api/devices/{code}/commands", {"action": "SELF_DESTRUCT", "params": {}}, tok)[0]
        c.check(st == 400, f"Lệnh không hỗ trợ -> HTTP {st} (mong đợi 400)")
        dev.auto_ack = False
        st, cmd3 = api.call("POST", f"/api/devices/{code}/commands?waitMs=1000", {"action": "PING", "params": {}}, tok)
        final = wait_until(lambda: (lambda x: x if x["status"] == "TIMEOUT" else None)(
            api.call("GET", f"/api/devices/{code}/commands/{cmd3['id']}", token=tok)[1]), 16, 1)
        c.check(final is not None, "Thiết bị không phản hồi -> lệnh chuyển TIMEOUT sau 10 s")
        dev.auto_ack = True


def f6_config() -> None:
    with Case(report, "F6", "Thay đổi cấu hình thiết bị từ xa", "4.4b · 8.4-6") as c:
        code, tok, dev = S["code"], S["owner"], S["dev"]
        n0 = len(dev.configs)
        st, cfgd = api.call("PUT", f"/api/devices/{code}/config", {"telemetryIntervalSec": 3, "gasThreshold": 500, "autoFan": True}, tok)
        c.check(st == 200 and cfgd["status"] in ("PENDING", "APPLIED"), f"Lưu cấu hình v{cfgd.get('configVersion')} -> HTTP {st}")
        got = wait_until(lambda: len(dev.configs) > n0, 5)
        c.check(got and dev.configs[-1].get("gasThreshold") == 500, "Thiết bị nhận cấu hình (bản tin retained) với ngưỡng 500")
        d = wait_until(lambda: (lambda x: x if x["status"] == "APPLIED" else None)(
            api.call("GET", f"/api/devices/{code}/config", token=tok)[1]), 6)
        c.check(d and d["appliedVersion"] == d["configVersion"], f"Thiết bị xác nhận APPLIED, phiên bản áp dụng {d and d['appliedVersion']}")
        st = api.call("PUT", f"/api/devices/{code}/config", {"telemetryIntervalSec": 0}, tok)[0]
        c.check(st == 400, f"Cấu hình sai (chu kỳ 0 s) -> HTTP {st} (mong đợi 400)")


def f7_offline() -> None:
    with Case(report, "F7", "Phát hiện thiết bị offline và trở lại online", "7.1 · 4.4d · 8.4-7") as c:
        code, tok = S["code"], S["owner"]
        S["dev"].kill()
        t0 = time.time()
        d = wait_until(lambda: (lambda x: x if x["connectivity"] == "OFFLINE" else None)(device(code, tok)), 40, 0.5)
        c.check(d is not None, f"Ngắt đột ngột (mất điện) -> OFFLINE sau {time.time() - t0:.1f} s (LWT của broker)")
        S["offline_detect_s"] = time.time() - t0
        a = wait_until(lambda: [x for x in alerts(code, tok) if x["type"] == "OFFLINE"], 6)
        c.check(a, "Sinh cảnh báo mất kết nối (OFFLINE)")
        st, b = api.call("POST", f"/api/devices/{code}/commands", {"action": "PING", "params": {}}, tok)
        c.check(st == 409, f"Gửi lệnh khi offline -> HTTP {st} {b.get('error') if isinstance(b, dict) else ''} (mong đợi 409)")
        dev = SimDevice(cfg, code, S["pw"])
        c.check(dev.connect() == 0, "Thiết bị kết nối lại")
        dev.telemetry(gas=45, temp=29)
        d = wait_until(lambda: (lambda x: x if x["connectivity"] == "ONLINE" else None)(device(code, tok)), 8)
        c.check(d is not None, "Trạng thái trở lại ONLINE")
        S["dev"] = dev
        if os.environ.get("GG_FULL"):
            # Mất kết nối không có LWT (thiết bị treo nhưng TCP còn): watchdog backend dựa trên lastSeen
            dev.client.loop_stop()
            t1 = time.time()
            d = wait_until(lambda: (lambda x: x if x["connectivity"] == "OFFLINE" else None)(device(code, tok)), 70, 1)
            c.check(d is not None, f"Thiết bị im lặng (không LWT) -> watchdog đánh OFFLINE sau {time.time() - t1:.0f} s")
            dev.close()
            dev = SimDevice(cfg, code, S["pw"])
            dev.connect()
            dev.telemetry(gas=45, temp=29)
            S["dev"] = dev


def f8_alerts() -> None:
    with Case(report, "F8", "Sinh cảnh báo theo luật và xử lý cảnh báo", "7.1 · 4.4d · 8.4-8") as c:
        code, tok, dev = S["code"], S["owner"], S["dev"]
        for a in alerts(code, tok):
            api.call("POST", f"/api/alerts/{a['id']}/resolve", {"note": "dọn trước khi test"}, tok)
        ws = S["ws_owner"]
        n0 = len(ws.messages)
        dev.telemetry(gas=1200, temp=30)
        got = wait_until(lambda: [x for x in alerts(code, tok) if x["type"] == "THRESHOLD"], 6)
        sev = sorted({x["severity"] for x in got or []})
        c.check("CRITICAL" in sev and "WARNING" in sev, f"Gas 1200 ppm -> cảnh báo {sev} theo luật mặc định (>400, >1000)")
        crit = next((x for x in got if x["severity"] == "CRITICAL"), None)
        if crit:
            c.note(f"Nội dung: {crit['message']}")
        c.check(ws.wait_for(lambda m: m.get("type") == "alert" and m.get("change") == "CREATED", 5) is not None,
                "Dashboard nhận thông báo cảnh báo mới qua WebSocket")
        st = api.call("POST", f"/api/alerts/{crit['id']}/ack", {"note": "viewer thử"}, S["viewer"])[0] if crit else 0
        c.check(st in (403, 404), f"Người không có quyền xác nhận cảnh báo -> HTTP {st}")
        st, a = api.call("POST", f"/api/alerts/{crit['id']}/ack", {"note": "Đang kiểm tra"}, tok)
        c.check(st == 200 and a["status"] == "ACKNOWLEDGED" and a["acknowledgedBy"] == S["owner_name"],
                f"Chủ thiết bị xác nhận -> {a.get('status')} bởi {a.get('acknowledgedBy')}")
        dev.telemetry(gas=60, temp=30)
        cleared = wait_until(lambda: (lambda x: x if x.get("clearedAt") else None)(
            api.call("GET", "/api/alerts" + q({"deviceCode": code, "size": 50}), token=tok)[1]["items"][0]), 6)
        c.check(cleared is not None, "Gas trở lại bình thường -> cảnh báo được đánh dấu đã hết điều kiện")
        st, a = api.call("POST", f"/api/alerts/{crit['id']}/resolve", {"note": "Đã thông gió"}, tok)
        c.check(st == 200 and a["status"] == "RESOLVED" and a["resolutionNote"] == "Đã thông gió",
                f"Xử lý xong -> {a.get('status')}, ghi chú '{a.get('resolutionNote')}'")
        for x in alerts(code, tok):
            api.call("POST", f"/api/alerts/{x['id']}/resolve", {"note": "dọn"}, tok)


def f9_suspend_decommission() -> None:
    with Case(report, "F9", "Khóa (tạm ngưng) và ngừng sử dụng thiết bị", "7.1 · 4.4b · 4.5 · 8.4-10") as c:
        code, tok, dev = S["code"], S["owner"], S["dev"]
        st = api.call("POST", f"/api/devices/{code}/lifecycle", {"action": "SUSPEND"}, tok)[0]
        c.check(st == 400, f"Tạm khóa không ghi lý do -> HTTP {st} (bắt buộc lý do)")
        st, d = api.call("POST", f"/api/devices/{code}/lifecycle", {"action": "SUSPEND", "reason": "E2E: nghi bị chiếm quyền"}, tok)
        c.check(st == 200 and d["lifecycleStatus"] == "SUSPENDED", f"Tạm khóa -> {d.get('lifecycleStatus')}")
        kicked = wait_until(lambda: not dev.connected.is_set(), 6)
        c.check(kicked, f"Broker ngắt ngay phiên MQTT đang mở (mã {dev.disconnect_rc})")
        dev.close()
        d2 = SimDevice(cfg, code, S["pw"])
        rc = d2.connect()
        c.check(rc in DENIED, f"Kết nối lại khi đang tạm khóa -> CONNACK {rc} (bị từ chối)")
        st = api.call("POST", f"/api/devices/{code}/commands", {"action": "PING", "params": {}}, tok)[0]
        c.check(st == 409, f"Gửi lệnh tới thiết bị bị khóa -> HTTP {st}")
        st, d = api.call("POST", f"/api/devices/{code}/lifecycle", {"action": "RESUME"}, tok)
        d3 = SimDevice(cfg, code, S["pw"])
        c.check(st == 200 and d3.connect() == 0, f"Mở khóa (RESUME) -> {d.get('lifecycleStatus')}, thiết bị kết nối lại được")
        d3.close()
        st, d = api.call("POST", f"/api/devices/{code}/lifecycle", {"action": "DECOMMISSION", "reason": "E2E: thay thiết bị"}, tok)
        c.check(st == 200 and d["lifecycleStatus"] == "DECOMMISSIONED", f"Ngừng sử dụng -> {d.get('lifecycleStatus')}")
        d4 = SimDevice(cfg, code, S["pw"])
        rc = d4.connect()
        c.check(rc in DENIED, f"Thiết bị đã ngừng sử dụng kết nối -> CONNACK {rc} (bị từ chối vĩnh viễn)")
        lst = api.call("GET", "/api/devices", token=tok)[1]
        c.check(all(x["deviceCode"] != code for x in lst), "Không còn trong danh sách thiết bị đang dùng")
        st, page = api.call("GET", f"/api/devices/{code}/telemetry?limit=5", token=tok)
        c.check(st == 200 and page["count"] > 0, "Dữ liệu lịch sử vẫn được giữ để tra cứu")
        st = api.call("POST", f"/api/devices/{code}/lifecycle", {"action": "RESUME"}, tok)[0]
        c.check(st in (400, 409), f"Không thể kích hoạt lại thiết bị đã ngừng sử dụng -> HTTP {st}")
        logs = api.call("GET", "/api/admin/audit-logs" + q({"targetId": code, "action": "DEVICE_LIFECYCLE", "size": 50}),
                        token=S["admin"])[1]["items"]
        c.check(len(logs) >= 3, f"Audit log ghi {len(logs)} thao tác vòng đời (khóa / mở khóa / ngừng sử dụng) kèm lý do")


# ============================================================================ 4.5 – bảo mật
def s1_other_users_device() -> None:
    with Case(report, "S1", "Người dùng không truy cập được thiết bị của người khác", "4.5 · 8.4-9") as c:
        code_b, tok = S["code_b"], S["owner"]
        checks = [("GET", f"/api/devices/{code_b}", None), ("GET", f"/api/devices/{code_b}/telemetry", None),
                  ("GET", f"/api/devices/{code_b}/config", None),
                  ("POST", f"/api/devices/{code_b}/commands", {"action": "FAN_SET", "params": {"on": True}}),
                  ("PUT", f"/api/devices/{code_b}/config", {"telemetryIntervalSec": 5}),
                  ("POST", f"/api/devices/{code_b}/lifecycle", {"action": "SUSPEND", "reason": "tấn công"})]
        for m, path, body in checks:
            st = api.call(m, path, body, tok)[0]
            c.check(st in (403, 404), f"{m} {path.replace(code_b, '{thiết bị người khác}')} -> HTTP {st}")
        lst = api.call("GET", "/api/devices", token=tok)[1]
        c.check(all(x["deviceCode"] != code_b for x in lst), "Danh sách thiết bị không lộ thiết bị của người khác")
        ws = StompClient(cfg, tok)
        ws.subscribe(code_b)
        S["dev_b"].telemetry(gas=33.3, temp=25)
        leaked = ws.wait_for(lambda m: m.get("deviceCode") == code_b, 3)
        c.check(leaked is None, f"Đăng ký kênh realtime của thiết bị người khác -> không nhận được dữ liệu {ws.errors[:1]}")
        ws.close()
        logs = api.call("GET", "/api/admin/audit-logs" + q({"actor": S["owner_name"], "result": "DENIED", "size": 50}),
                        token=S["admin"])[1]["items"]
        c.check(len(logs) >= 3, f"Audit log ghi {len(logs)} lần bị từ chối của {S['owner_name']}")


def s2_shared_viewer() -> None:
    with Case(report, "S2", "Người được chia sẻ chỉ được xem, không được điều khiển", "4.4a · 4.5") as c:
        code, tok, vtok = S["code"], S["owner"], S["viewer"]
        st = api.call("POST", f"/api/devices/{code}/shares", {"username": S["viewer_name"]}, tok)[0]
        c.check(st in (200, 201), f"Chủ thiết bị chia sẻ cho {S['viewer_name']} -> HTTP {st}")
        st, d = api.call("GET", f"/api/devices/{code}", token=vtok)
        c.check(st == 200 and d["canManage"] is False, f"Người xem đọc được thiết bị (canManage={d.get('canManage')})")
        c.check(api.call("GET", f"/api/devices/{code}/telemetry/latest", token=vtok)[0] == 200, "Người xem đọc được dữ liệu")
        for m, path, body in [("POST", f"/api/devices/{code}/commands", {"action": "FAN_SET", "params": {"on": False}}),
                              ("PUT", f"/api/devices/{code}/config", {"telemetryIntervalSec": 9}),
                              ("POST", f"/api/devices/{code}/credentials/rotate", None),
                              ("POST", f"/api/devices/{code}/lifecycle", {"action": "SUSPEND", "reason": "x"})]:
            st = api.call(m, path, body, vtok)[0]
            c.check(st == 403, f"Người xem {m} {path.split(code)[1]} -> HTTP {st} (mong đợi 403, kiểm tra ở backend)")


def s3_tokens() -> None:
    with Case(report, "S3", "API từ chối token thiếu, sai, giả mạo, hết hạn hoặc đã thu hồi", "4.5") as c:
        st = api.call("GET", "/api/devices")[0]
        c.check(st == 401, f"Không có token -> HTTP {st}")
        st = api.call("GET", "/api/devices", token="abc.def.ghi")[0]
        c.check(st == 401, f"Token rác -> HTTP {st}")
        good = S["owner"]
        h, p, sig = good.split(".")
        tampered = f"{h}.{p}.{sig[:-4]}{'AAAA' if not sig.endswith('AAAA') else 'BBBB'}"
        st = api.call("GET", "/api/devices", token=tampered)[0]
        c.check(st == 401, f"Token bị sửa chữ ký -> HTTP {st}")
        import base64 as b64
        pl = json.loads(b64.urlsafe_b64decode(p + "=" * (-len(p) % 4)))
        pl["role"] = "ADMIN"
        forged_payload = b64.urlsafe_b64encode(json.dumps(pl).encode()).rstrip(b"=").decode()
        st = api.call("GET", "/api/admin/users", token=f"{h}.{forged_payload}.{sig}")[0]
        c.check(st == 401, f"Tự sửa vai trò trong token thành ADMIN -> HTTP {st}")
        if cfg.jwt_secret:
            expired = forge_expired_token(good, cfg.jwt_secret)
            st, b = api.call("GET", "/api/devices", token=expired)
            c.check(st == 401, f"Token đúng chữ ký nhưng đã hết hạn -> HTTP {st}")
        else:
            c.note("Không có JWT_SECRET trong .env -> bỏ qua ca token hết hạn")
        tmp = api.login(S["owner_name"], S["owner_pw"])
        api.call("POST", "/api/auth/logout", token=tmp)
        c.check(api.call("GET", "/api/devices", token=tmp)[0] == 401, "Token sau khi đăng xuất bị thu hồi -> 401")
        ws = StompClient(cfg, "abc.def.ghi")
        c.check(not ws.connected, "WebSocket từ chối CONNECT với token sai")
        S["owner"] = api.login(S["owner_name"], S["owner_pw"])


def s4_broker_acl() -> None:
    with Case(report, "S4", "Broker từ chối kết nối sai và publish/subscribe sai topic", "4.5 · 4.3") as c:
        dev, code_b = S["dev"], S["code_b"]
        before = api.call("GET", f"/api/devices/{code_b}/telemetry?limit=1000", token=S["other"])[1]["count"]
        rc = dev.publish(f"iot/v1/devices/{code_b}/telemetry",
                         {"deviceId": code_b, "seq": 999999, "ts": now_ms(), "gas": {"value": 9999, "unit": "ppm"}})
        c.check(rc == REASON_NOT_AUTHORIZED, f"Thiết bị A publish vào topic của thiết bị B -> PUBACK {rc} (Not authorized)")
        time.sleep(1.5)
        after = api.call("GET", f"/api/devices/{code_b}/telemetry?limit=1000", token=S["other"])[1]["count"]
        c.check(after == before, f"Dữ liệu giả không vào được hệ thống ({before} -> {after} điểm)")
        rc = dev.publish(f"iot/v1/devices/{dev.code}/cmd", {"commandId": "fake", "action": "FAN_SET", "params": {"on": True}})
        c.check(rc == REASON_NOT_AUTHORIZED, f"Thiết bị tự publish lệnh vào topic cmd -> PUBACK {rc}")
        codes = dev.subscribe_raw(f"iot/v1/devices/{code_b}/cmd")
        c.check(codes and codes[0] == REASON_NOT_AUTHORIZED, f"Subscribe lệnh của thiết bị B -> SUBACK {codes}")
        codes = dev.subscribe_raw("iot/v1/devices/#")
        c.check(codes and codes[0] == REASON_NOT_AUTHORIZED, f"Subscribe wildcard toàn hệ thống -> SUBACK {codes}")
        bad = SimDevice(cfg, dev.code, "sai-mat-khau-123", client_id=dev.code + "-x")
        rc = bad.connect(status_online=False)
        c.check(rc in DENIED, f"Kết nối sai mật khẩu -> CONNACK {rc}")
        imp = SimDevice(cfg, code_b, S["pw"], client_id=code_b)
        rc = imp.connect(status_online=False)
        c.check(rc in DENIED, f"Dùng mật khẩu của A để đăng nhập tên B -> CONNACK {rc}")
        wrong_cid = SimDevice(cfg, dev.code, S["pw"], client_id="hacker-" + dev.code)
        rc = wrong_cid.connect(status_online=False)
        c.check(rc in DENIED, f"Đúng mật khẩu nhưng clientId khác mã thiết bị -> CONNACK {rc}")
        anon = SimDevice(cfg, "", "", client_id=f"anon-{dev.code}")
        anon.client.username_pw_set(None, None)
        rc = anon.connect(status_online=False)
        c.check(rc in DENIED, f"Kết nối ẩn danh -> CONNACK {rc}")
        c.check(dev.connected.is_set(), "Thiết bị hợp lệ vẫn hoạt động bình thường")


def s5_invalid_data() -> None:
    with Case(report, "S5", "Hệ thống kiểm tra và từ chối dữ liệu không hợp lệ", "4.5 · 4.4c") as c:
        dev, code, tok = S["dev"], S["code"], S["owner"]
        before = ingest_stats()["rejectedByReason"]
        base = lambda **kw: {"deviceId": code, "seq": 500000, "ts": now_ms(), "gas": {"value": 50, "unit": "ppm"}, **kw}  # noqa: E731
        cases = [("MALFORMED_JSON", b"{gas: 50"),
                 ("MISSING_TS", {"deviceId": code, "seq": 500001, "gas": {"value": 50, "unit": "ppm"}}),
                 ("TS_IN_FUTURE", base(seq=500002, ts=now_ms() + 3_600_000)),
                 ("TS_TOO_OLD", base(seq=500003, ts=now_ms() - 30 * 86_400_000)),
                 ("OUT_OF_RANGE", base(seq=500004, gas={"value": 99999, "unit": "ppm"})),
                 ("WRONG_UNIT", base(seq=500005, gas={"value": 50, "unit": "mg/m3"})),
                 ("UNKNOWN_FIELD", base(seq=500006, sql="'; DROP TABLE telemetry;--")),
                 ("DEVICE_ID_MISMATCH", base(seq=500007, deviceId="someone-else")),
                 ("PAYLOAD_TOO_LARGE", base(seq=500008, fw="x" * 5000))]
        for reason, payload in cases:
            dev.publish(f"iot/v1/devices/{code}/telemetry", payload)
        time.sleep(2)
        after = ingest_stats()["rejectedByReason"]
        for reason, _ in cases:
            key = f"telemetry:{reason}"
            inc = after.get(key, 0) - before.get(key, 0)
            c.check(inc >= 1, f"Bản tin lỗi {reason} bị từ chối (bộ đếm +{inc})")
        page = api.call("GET", f"/api/devices/{code}/telemetry?limit=1000", token=tok)[1]
        c.check(not any((p.get("seq") or 0) >= 500000 for p in page["points"]), "Không bản tin lỗi nào được lưu vào CSDL")
        st, b = api.call("POST", "/api/devices", {"deviceCode": "BAD CODE<script>", "name": ""}, tok)
        c.check(st == 400 and isinstance(b, dict) and b.get("fieldErrors"), f"REST: dữ liệu sai -> HTTP {st}, lỗi theo trường {list((b or {}).get('fieldErrors', {}))}")


def s6_bruteforce() -> None:
    with Case(report, "S6", "Chống dò mật khẩu: khóa tạm sau 5 lần sai", "4.5 · 4.4a") as c:
        name, pw = S["lock_name"], S["lock_pw"]
        codes = [api.call("POST", "/api/auth/login", {"username": name, "password": "Sai12345"})[0] for _ in range(5)]
        c.check(codes[:4] == [401] * 4 and codes[4] == 423,
                f"Sai mật khẩu 5 lần liên tiếp -> {codes} (lần thứ 5 kích hoạt khóa tạm 15 phút: 423)")
        st, b = api.call("POST", "/api/auth/login", {"username": name, "password": pw})
        c.check(st == 423, f"Lần 6 dù ĐÚNG mật khẩu vẫn bị khóa tạm -> HTTP {st}")
        st = api.call("POST", f"/api/admin/users/{S['lock_id']}/unlock", token=S["admin"])[0]
        st2 = api.call("POST", "/api/auth/login", {"username": name, "password": pw})[0]
        c.check(st == 200 and st2 == 200, f"Admin mở khóa -> đăng nhập lại được ({st2})")


def s7_rotate_revoke() -> None:
    with Case(report, "S7", "Thu hồi thông tin xác thực: mật khẩu cũ của thiết bị hết hiệu lực", "4.5 · 4.4b") as c:
        code, tok = S["code"], S["owner"]
        st, p = api.call("POST", f"/api/devices/{code}/credentials/rotate", token=tok)
        c.check(st in (200, 201) and p["password"] != S["pw"], "Đổi mật khẩu thiết bị (rotate) -> cấp mật khẩu mới")
        kicked = wait_until(lambda: not S["dev"].connected.is_set(), 6)
        c.check(kicked, "Phiên MQTT dùng mật khẩu cũ bị ngắt")
        S["dev"].close()
        old = SimDevice(cfg, code, S["pw"])
        c.check(old.connect() in DENIED, "Mật khẩu cũ bị từ chối -> thiết bị/kẻ lấy cắp mật khẩu không gửi dữ liệu được")
        S["pw"] = p["password"]
        dev = SimDevice(cfg, code, S["pw"])
        c.check(dev.connect() == 0, "Mật khẩu mới hoạt động")
        dev.telemetry(gas=44, temp=29)
        S["dev"] = dev


def _lan_ip() -> str | None:
    """IP LAN của máy chạy test (không gửi gói tin nào, chỉ hỏi bảng định tuyến)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sk:
            sk.connect(("8.8.8.8", 80))
            ip = sk.getsockname()[0]
        return None if ip.startswith("127.") else ip
    except OSError:
        return None


def _tcp_open(host: str, port: int) -> bool:
    try:
        with socket.create_connection((host, port), timeout=3):
            return True
    except OSError:
        return False


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):  # noqa: ANN002, ANN003
        return None


def _http(url: str, ctx: ssl.SSLContext | None = None, data: dict | None = None,
          headers: dict | None = None) -> tuple[int, dict, bytes]:
    req = urllib.request.Request(url, data=json.dumps(data).encode() if data is not None else None,
                                 method="POST" if data is not None else "GET")
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    opener = urllib.request.build_opener(_NoRedirect, urllib.request.HTTPSHandler(context=ctx))
    try:
        with opener.open(req, timeout=10) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def s9_tls() -> None:
    with Case(report, "S9", "Kết nối mã hóa TLS: MQTT 8883 và web HTTPS", "4.5") as c:
        ca = Path(cfg.ca_cert)
        if not ca.exists():
            c.skip(f"Chưa có chứng chỉ ({ca}). Tạo bằng deployment/certs/gen-certs.sh rồi chạy lại hệ thống.")
            return
        good = ssl.create_default_context(cafile=str(ca))
        code = f"e2e-{RID}-tls"
        S["code_tls"] = code
        pw = provision(code, S["owner"], "E2E thiết bị TLS")

        # 1) Thiết bị kết nối broker qua TLS bằng CA của nhóm, dữ liệu vào hệ thống như bình thường
        dev = SimDevice(cfg, code, pw, tls=good)
        rc = dev.connect()
        sock = dev.client.socket()
        ver = sock.version() if isinstance(sock, ssl.SSLSocket) else None
        cipher = sock.cipher()[0] if isinstance(sock, ssl.SSLSocket) else None
        c.check(rc == 0 and ver in ("TLSv1.2", "TLSv1.3"),
                f"Thiết bị kết nối MQTT qua TLS cổng {cfg.mqtts_port}, kiểm tra chứng chỉ broker bằng CA của nhóm "
                f"-> CONNACK {rc}, {ver}, {cipher}")
        dev.telemetry(gas=33, temp=27.5)
        d = wait_until(lambda: (lambda x: x if x.get("lifecycleStatus") == "ACTIVE" else None)(device(code, S["owner"])), 8)
        n = api.call("GET", f"/api/devices/{code}/telemetry?limit=10", token=S["owner"])[1].get("count", 0)
        c.check(d and n >= 1, f"Dữ liệu gửi qua TLS được kiểm tra và lưu như bình thường ({n} điểm, thiết bị {d and d['lifecycleStatus']})")
        dev.close()

        # 2) Gửi MQTT không mã hóa vào cổng TLS -> broker không chấp nhận
        plain = SimDevice(cfg, code, pw, client_id=code, port=cfg.mqtts_port)
        try:
            rc = plain.connect(timeout=4, status_online=False)
        except RuntimeError:
            rc = None
        c.check(rc is None, f"MQTT không mã hóa vào cổng {cfg.mqtts_port} -> không có CONNACK (broker đóng kết nối)")

        # 3) Broker giả / chứng chỉ không do CA của nhóm ký -> thiết bị từ chối (chống nghe lén kiểu man-in-the-middle)
        wrong = ssl.create_default_context()   # chỉ tin các CA công cộng, không tin CA của nhóm
        try:
            rc = SimDevice(cfg, code, pw, tls=wrong).connect(timeout=4, status_online=False)
            why = f"CONNACK {rc}"
        except RuntimeError as e:
            rc, why = None, ("CERTIFICATE_VERIFY_FAILED" if "CERTIFICATE_VERIFY_FAILED" in str(e) else str(e)[:80])
        c.check(rc is None and "VERIFY" in why, f"Chứng chỉ broker không do CA tin cậy ký -> client từ chối ({why})")

        # 4) Cổng MQTT không mã hóa không mở ra mạng LAN, cổng TLS thì có
        lan = _lan_ip()
        if lan:
            c.check(not _tcp_open(lan, cfg.mqtt_port) and _tcp_open(lan, cfg.mqtts_port),
                    f"Từ địa chỉ LAN {lan}: cổng {cfg.mqtt_port} (không mã hóa) đóng, cổng {cfg.mqtts_port} (TLS) mở")
        else:
            c.note("Không xác định được IP LAN -> bỏ qua kiểm tra cổng trên LAN")

        # 5) Web: HTTP chuyển hướng sang HTTPS; HTTPS dùng chứng chỉ của nhóm; đăng nhập qua HTTPS
        try:
            st, h, _ = _http(cfg.web_http + "/devices")
        except OSError:
            st, h = None, {}
        if st is None:
            c.note(f"Không truy cập được web {cfg.web_http} (chưa chạy frontend bằng Docker) -> bỏ qua phần HTTPS")
        else:
            loc = h.get("Location", "")
            c.check(st in (301, 308) and loc.startswith("https://"), f"Web HTTP -> HTTP {st}, chuyển hướng tới {loc}")
            st, h, _ = _http(cfg.web_http + "/api/auth/login", data={"username": "x", "password": "y"})
            c.check(st in (301, 308), f"Gọi API đăng nhập qua HTTP -> HTTP {st} (không xử lý qua kết nối không mã hóa)")
            st, h, body = _http(cfg.web_https + "/", ctx=good)
            c.check(st == 200, f"Trang web qua HTTPS với chứng chỉ của nhóm -> HTTP {st}")
            st, _, body = _http(cfg.web_https + "/api/auth/login", ctx=good,
                                data={"username": S["viewer_name"], "password": S["viewer_pw"]},
                                headers={"Origin": cfg.web_https})
            c.check(st == 200 and b"token" in body, f"Đăng nhập qua HTTPS (cùng origin) -> HTTP {st}")
            try:
                _http(cfg.web_https + "/", ctx=ssl.create_default_context())
                ok = False
            except urllib.error.URLError as e:
                ok = "CERTIFICATE_VERIFY_FAILED" in str(e.reason)
            c.check(ok, "Trình duyệt/client chưa tin CA của nhóm -> cảnh báo chứng chỉ (CERTIFICATE_VERIFY_FAILED)")


def s8_audit_and_storage() -> None:
    with Case(report, "S8", "Audit log thao tác quan trọng và mật khẩu được băm", "4.5") as c:
        logs = api.call("GET", "/api/admin/audit-logs" + q({"targetId": S["code"], "size": 200}), token=S["admin"])[1]["items"]
        actions = {x["action"] for x in logs}
        for a in ["DEVICE_REGISTER", "DEVICE_PROVISION", "COMMAND_SEND", "DEVICE_CONFIG_UPDATE", "DEVICE_CREDENTIAL_ROTATE",
                  "DEVICE_SHARE_ADD"]:
            c.check(any(x.startswith(a) for x in actions), f"Audit có '{a}' cho {S['code']}")
        c.note(f"Các hành động đã ghi: {', '.join(sorted(actions))}")
        st, users = api.call("GET", "/api/admin/users", token=S["admin"])
        c.check(st == 200 and not any("password" in k.lower() for u in users for k in u), "API người dùng không trả mật khẩu/băm")
        h = db_query(f"select password_hash from users where username='{S['owner_name']}'")
        if h is None:
            c.note("Không truy cập được CSDL (docker exec iot-postgres) -> bỏ qua kiểm tra băm trong CSDL")
        else:
            c.check(h.startswith("$2") and S["owner_pw"] not in h, f"CSDL lưu BCrypt: {h[:7]}… (không có mật khẩu thuần)")


# ============================================================================ dọn dẹp + chạy
def cleanup() -> None:
    for k in ("dev", "dev_b"):
        if S.get(k):
            S[k].close()
    for code, tok in [(S.get("code_b"), S.get("other")), (S.get("code_tls"), S.get("owner"))]:
        if code and tok:
            api.call("POST", f"/api/devices/{code}/lifecycle", {"action": "DECOMMISSION", "reason": "dọn dữ liệu e2e"}, tok)
    for key in ("owner", "viewer", "other", "lock"):
        if S.get(f"{key}_id"):
            api.call("POST", f"/api/admin/users/{S[key + '_id']}/lock", token=S["admin"])
    if S.get("reg_id"):
        api.call("POST", f"/api/admin/users/{S['reg_id']}/lock", token=S["admin"])
    if S.get("ws_owner"):
        S["ws_owner"].close()


def main() -> None:
    setup()
    steps = [f1_login_roles, f2_register_activate, f3_telemetry, f4_dashboard_realtime, f5_commands, f6_config,
             s1_other_users_device, s2_shared_viewer, s3_tokens, s4_broker_acl, s5_invalid_data, s6_bruteforce,
             f8_alerts, f7_offline, s7_rotate_revoke, s9_tls, s8_audit_and_storage, f9_suspend_decommission]
    try:
        for fn in steps:
            fn()
    finally:
        cleanup()
    order = lambda c: (c["id"][0] != "F", int(c["id"][1:]))  # noqa: E731
    report.cases.sort(key=order)
    path = report.write()
    passed = sum(c["status"] == "PASS" for c in report.cases)
    print(f"\n==== {passed}/{len(report.cases)} ca đạt. Báo cáo: {path}")
    sys.exit(0 if passed == len(report.cases) else 1)


if __name__ == "__main__":
    main()
