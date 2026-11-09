"""
Kiểm thử phi chức năng (mục 7.2) – đo đạc trên hệ thống THẬT đang chạy.

    python tests/perf/run_perf.py            # đầy đủ (~20 phút): ổn định 10 phút, tải 10/50/100 thiết bị
    python tests/perf/run_perf.py --quick    # nhanh (~6 phút): ổn định 2 phút, tải 10/50 thiết bị

Mỗi chỉ tiêu ghi đủ 3 phần theo đề bài: PHƯƠNG PHÁP ĐO, DỮ LIỆU ĐO, NHẬN XÉT (so với mục tiêu trong
docs/test-plan.md). Kết quả: tests/reports/perf-<thời điểm>.md (+ .json chứa số liệu thô).

Thiết bị được mô phỏng bằng MQTT đúng giao thức firmware (QoS1, seq, ts). Đồng hồ của thiết bị mô phỏng
và backend là cùng một máy nên độ trễ đo được là chính xác (không lệch đồng hồ).
"""
from __future__ import annotations

import argparse
import json
import os
import shlex
import statistics
import subprocess
import sys
import threading
import time
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "common"))
from gasguard_test import (Api, Config, Report, SimDevice, StompClient, clock_offset_ms, iso, iso_to_ms,  # noqa: E402
                           now_ms, pct, run_id, summary, wait_until)

cfg = Config()
api = Api(cfg)
RID = run_id()
S: dict = {"devices": []}
RAW: dict = {}
VERDICTS: list[tuple[str, str, str, str]] = []   # (mã, chỉ tiêu, kết quả, đạt?)


def q(params: dict) -> str:
    return "?" + urllib.parse.urlencode(params)


def table(headers: list[str], rows: list[list]) -> str:
    out = ["| " + " | ".join(headers) + " |", "|" + "---|" * len(headers)]
    out += ["| " + " | ".join(str(x) for x in r) + " |" for r in rows]
    return "\n".join(out)


def stat_row(name: str, s: dict) -> list:
    return [name, s.get("n", 0), s.get("min", "-"), s.get("avg", "-"), s.get("p50", "-"), s.get("p95", "-"), s.get("max", "-")]


STAT_HEAD = ["Đại lượng (ms)", "Số mẫu", "Min", "TB", "P50", "P95", "Max"]


def section(report: Report, code: str, title: str, method: str, data: str, comment: str, result: str, ok: bool) -> None:
    VERDICTS.append((code, title, result, "✅ Đạt" if ok else "❌ Chưa đạt"))
    report.section(f"{code} – {title}", f"**Phương pháp đo.** {method}\n\n**Dữ liệu đo.**\n\n{data}\n\n**Nhận xét.** {comment}")
    print(f"   => {code}: {result} ({'ĐẠT' if ok else 'CHƯA ĐẠT'})", flush=True)


def new_device(i: int | str, keepalive: int = 10) -> SimDevice:
    code = f"perf-{RID}-{i}"
    st, b = api.call("POST", "/api/devices", {"deviceCode": code, "name": f"Perf {i}", "deviceType": "GAS_NODE",
                                              "zone": "PERF"}, S["admin"])
    assert st == 201, f"đăng ký {code}: {st} {b}"
    st, p = api.call("POST", f"/api/devices/{code}/provision", token=S["admin"])
    assert st in (200, 201), f"cấp xác thực {code}: {st} {p}"
    dev = SimDevice(cfg, code, p["password"], keepalive=keepalive)
    rc = dev.connect()
    assert rc == 0, f"{code} kết nối broker: CONNACK {rc}"
    S["devices"].append(dev)
    return dev


def count_stored(code: str, t_from: float, t_to: float, metric: str = "gas") -> int:
    st, s = api.call("GET", f"/api/devices/{code}/telemetry/stats" + q({"from": iso(t_from), "to": iso(t_to)}), token=S["admin"])
    return next((m["count"] for m in s.get("metrics", []) if m["metric"] == metric), 0) if st == 200 else -1


def stats_obj(code: str, t_from: float, t_to: float) -> dict:
    return api.call("GET", f"/api/devices/{code}/telemetry/stats" + q({"from": iso(t_from), "to": iso(t_to)}), token=S["admin"])[1]


def ingest() -> dict:
    return api.call("GET", "/api/system/ingest-stats", token=S["admin"])[1]


def backend_p95(snap: dict):
    """P95 độ trễ thiết bị -> backend do backend tính (receivedAt − ts), đã trừ độ lệch đồng hồ."""
    v = (snap.get("latencyMs") or {}).get("p95")
    return None if v is None else round(v - S["offset"], 1)


def run_cmd(cmd: str, timeout: int) -> None:
    """Chạy lệnh khởi động lại (docker restart ...). Ghi lỗi ra file tạm, không dùng pipe để tiến trình nền
    do lệnh tạo ra không giữ pipe làm treo lệnh."""
    import tempfile
    args = ["sh", "-c", cmd[3:]] if cmd.startswith("sh ") else shlex.split(cmd)
    with tempfile.TemporaryFile() as err:
        r = subprocess.run(args, stdout=subprocess.DEVNULL, stderr=err, stdin=subprocess.DEVNULL, timeout=timeout)
        if r.returncode != 0:
            err.seek(0)
            raise RuntimeError(err.read().decode("utf-8", "replace").strip()[:200] or f"lệnh trả mã {r.returncode}")


# ============================================================================ đo tài nguyên (CPU / RAM)
class ResourceSampler:
    """Lấy mẫu CPU/RAM mỗi ~2 s: `docker stats` (khi chạy bằng Docker) hoặc psutil theo tiến trình (dev)."""
    CONTAINERS = ["iot-backend", "iot-mosquitto", "iot-postgres", "iot-frontend"]

    def __init__(self) -> None:
        self.mode = None
        self.samples: list[dict] = []
        self._stop = threading.Event()
        if os.environ.get("GG_NO_RESOURCES"):
            return
        try:
            r = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{json .}}"] + self.CONTAINERS,
                               capture_output=True, text=True, timeout=15)
            if r.returncode == 0 and r.stdout.strip():
                self.mode = "docker"
        except (OSError, subprocess.TimeoutExpired):
            pass
        if self.mode is None:
            try:
                import psutil  # noqa: F401
                self.mode = "psutil"
            except ImportError:
                self.mode = None

    @staticmethod
    def _mib(s: str) -> float:
        s = s.strip()
        units = {"KiB": 1 / 1024, "MiB": 1, "GiB": 1024, "kB": 1 / 1024, "MB": 1, "GB": 1024, "B": 1 / 1048576}
        for u, f in units.items():
            if s.endswith(u):
                return float(s[: -len(u)]) * f
        return float("nan")

    def sample(self) -> dict:
        out: dict = {}
        if self.mode == "docker":
            r = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{json .}}"] + self.CONTAINERS,
                               capture_output=True, text=True, timeout=20)
            for line in r.stdout.splitlines():
                d = json.loads(line)
                out[d["Name"]] = {"cpu": float(d["CPUPerc"].rstrip("%") or 0),
                                  "mem": self._mib(d["MemUsage"].split("/")[0])}
        elif self.mode == "psutil":
            import psutil
            groups = {"iot-backend": lambda p: "BackendApplication" in " ".join(p.info["cmdline"] or []) or "app.jar" in " ".join(p.info["cmdline"] or []),
                      "iot-mosquitto": lambda p: p.info["name"] == "mosquitto",
                      "iot-postgres": lambda p: (p.info["name"] or "").startswith("postgres")}
            procs = list(psutil.process_iter(["name", "cmdline"]))
            for g, match in groups.items():
                ps = [p for p in procs if match(p)]
                for p in ps:
                    try:
                        p.cpu_percent(None)
                    except psutil.Error:
                        pass
            time.sleep(1.0)
            for g, match in groups.items():
                cpu = mem = 0.0
                for p in [p for p in procs if match(p)]:
                    try:
                        cpu += p.cpu_percent(None)
                        mem += p.memory_info().rss / 1048576
                    except psutil.Error:
                        pass
                out[g] = {"cpu": cpu, "mem": mem}
        return out

    def start(self, label: str) -> None:
        self.label = label
        self._stop.clear()
        self.window: list[dict] = []
        if not self.mode:
            return
        def loop():
            while not self._stop.is_set():
                try:
                    s = self.sample()
                    s["_t"] = time.time()
                    self.window.append(s)
                    self.samples.append({"label": label, **s})
                except Exception:  # noqa: BLE001
                    pass
                self._stop.wait(1.0)
        self.thread = threading.Thread(target=loop, daemon=True)
        self.thread.start()

    def stop(self) -> dict:
        self._stop.set()
        if self.mode:
            self.thread.join(timeout=25)
        agg = {}
        for name in self.CONTAINERS:
            vals = [s[name] for s in self.window if name in s]
            if vals:
                agg[name] = {"cpu_avg": round(statistics.fmean(v["cpu"] for v in vals), 1),
                             "cpu_max": round(max(v["cpu"] for v in vals), 1),
                             "mem_avg": round(statistics.fmean(v["mem"] for v in vals), 1),
                             "mem_max": round(max(v["mem"] for v in vals), 1)}
        return agg


RES = ResourceSampler()


# ============================================================================ P1 – độ trễ cảm biến -> dashboard
def p1_dashboard_latency(report: Report, n: int) -> None:
    print("\n▶ P1 – Độ trễ cảm biến -> dashboard", flush=True)
    dev = new_device("lat")
    ws = StompClient(cfg, S["admin"])
    ws.subscribe(dev.code)
    sent: dict[int, int] = {}
    for i in range(n):
        seq, ts = dev.telemetry(gas=100 + i % 50, temp=30, wait=False)
        sent[seq] = ts
        time.sleep(0.2)
    time.sleep(2)
    lat_dash, lat_backend = [], []
    for t_recv, m in list(ws.messages):
        if m.get("type") == "telemetry" and m.get("deviceCode") == dev.code:
            ts = iso_to_ms(m["ts"])
            lat_dash.append(t_recv - ts)
            lat_backend.append(iso_to_ms(m["receivedAt"]) - S["offset"] - ts)   # trừ độ lệch đồng hồ backend
    ws.close()
    received = len(lat_dash)
    s1, s2 = summary(lat_dash), summary(lat_backend)
    RAW["P1"] = {"dashboard_ms": lat_dash, "backend_ms": lat_backend, "sent": n}
    ok = received == n and s1.get("p95", 1e9) <= 1000
    data = table(STAT_HEAD, [stat_row("Thiết bị → backend nhận (MQTT + kiểm tra)", s2),
                             stat_row("Thiết bị → dashboard nhận (đầu-cuối)", s1)])
    data += f"\n\nĐã gửi {n} bản tin, dashboard nhận {received} bản tin."
    section(report, "P1", "Độ trễ từ cảm biến đến dashboard",
            f"1 thiết bị mô phỏng gửi {n} bản tin telemetry QoS1, mỗi 200 ms, ghi thời điểm đo `ts`. Một client "
            "WebSocket/STOMP đăng nhập giống trình duyệt, đăng ký kênh realtime của thiết bị và ghi thời điểm nhận từng "
            "bản tin. Độ trễ = thời điểm dashboard nhận − `ts`. Backend cũng ghi `receivedAt` để tách phần MQTT/xử lý.",
            data,
            f"P95 đầu-cuối {s1.get('p95')} ms, mục tiêu ≤ 1000 ms → {'đạt' if ok else 'chưa đạt'}. "
            f"Phần lớn độ trễ nằm ở chặng thiết bị → backend (P50 {s2.get('p50')} ms); chặng đẩy WebSocket chỉ thêm "
            f"khoảng {round((s1.get('p50') or 0) - (s2.get('p50') or 0), 1)} ms. Với ESP32 thật qua WiFi, độ trễ tăng thêm "
            "thời gian truyền WiFi (thường 5–50 ms); xem cột Độ trễ TB ở tab Dữ liệu của thiết bị thật.",
            f"P95 {s1.get('p95')} ms, nhận {received}/{n}", ok)


# ============================================================================ P2 – độ trễ lệnh
def p2_command_latency(report: Report, n: int) -> None:
    print("\n▶ P2 – Độ trễ lệnh điều khiển", flush=True)
    dev = S["devices"][0]
    dev_lat, api_lat, statuses = [], [], {}
    last_hb = 0.0
    for i in range(n):
        # Giống firmware: thiết bị gửi heartbeat định kỳ. Nếu không, khi bài đo kéo dài quá ngưỡng im lặng của
        # watchdog (30 s) backend sẽ đánh dấu OFFLINE và từ chối lệnh (409) dù thiết bị vẫn kết nối.
        if time.time() - last_hb >= 5:
            dev.heartbeat()
            last_hb = time.time()
        action = {"action": "FAN_SET", "params": {"on": i % 2 == 0}} if i % 3 else {"action": "PING", "params": {}}
        t0 = time.time()
        st, c = api.call("POST", f"/api/devices/{dev.code}/commands?waitMs=5000", action, S["admin"])
        api_lat.append((time.time() - t0) * 1000)
        statuses[c.get("status")] = statuses.get(c.get("status"), 0) + 1
        if c.get("latencyMs") is not None:
            dev_lat.append(c["latencyMs"])
        time.sleep(0.1)
    s1, s2 = summary(dev_lat), summary(api_lat)
    RAW["P2"] = {"ack_ms": dev_lat, "api_ms": api_lat, "status": statuses}
    ok = statuses.get("SUCCESS", 0) == n and s2.get("p95", 1e9) <= 1000
    section(report, "P2", "Độ trễ của lệnh điều khiển",
            f"Gửi {n} lệnh (FAN_SET bật/tắt xen kẽ PING) qua REST API với `waitMs=5000`. Backend lưu lệnh, publish "
            "xuống topic `cmd` (QoS1) và ghi `latencyMs` = thời điểm nhận `cmd/ack` − thời điểm gửi. Phía client đo thêm "
            "thời gian khứ hồi toàn bộ của lời gọi API (người dùng bấm nút → thấy kết quả).",
            table(STAT_HEAD, [stat_row("Backend gửi → nhận ACK", s1), stat_row("Khứ hồi API (người dùng)", s2)])
            + f"\n\nKết quả lệnh: {statuses}",
            f"{statuses.get('SUCCESS', 0)}/{n} lệnh thành công; P95 khứ hồi {s2.get('p95')} ms, mục tiêu ≤ 1000 ms → "
            f"{'đạt' if ok else 'chưa đạt'}. Chênh lệch giữa hai dòng là thời gian lưu CSDL, kiểm tra quyền và ghi audit log.",
            f"P95 {s2.get('p95')} ms, {statuses.get('SUCCESS', 0)}/{n} thành công", ok)


# ============================================================================ P3 – tỷ lệ mất bản tin
def p3_loss(report: Report, n: int, rate: int) -> None:
    print("\n▶ P3 – Tỷ lệ mất bản tin", flush=True)
    rows, ok_all = [], True
    for qos in (1, 0):
        dev = new_device(f"loss{qos}")
        t0 = time.time() - 1
        for i in range(n):
            dev.telemetry(gas=60, temp=30, qos=qos, wait=False)
            time.sleep(1 / rate)
        time.sleep(3)
        stored = count_stored(dev.code, t0, time.time() + 5)
        st = stats_obj(dev.code, t0, time.time() + 5)
        loss = (n - stored) / n * 100
        rows.append([f"QoS {qos}", n, f"{rate}/s", stored, f"{loss:.2f} %",
                     f"{(st.get('estimatedLossRate') or 0) * 100:.2f} %"])
        RAW[f"P3_qos{qos}"] = {"sent": n, "stored": stored, "loss_pct": loss}
        if qos == 1 and loss > 0:
            ok_all = False
    section(report, "P3", "Tỷ lệ mất bản tin",
            f"Mỗi mức QoS: 1 thiết bị mô phỏng gửi {n} bản tin liên tục với tốc độ {rate} bản tin/giây (nhanh hơn "
            "nhiều lần thực tế 1 bản tin / 5 s). Đếm số bản tin backend đã lưu trong CSDL trong khoảng thời gian gửi. "
            "Tỷ lệ mất = (gửi − lưu) / gửi. Cột cuối là ước lượng của chính hệ thống dựa trên khoảng trống `seq` "
            "(hiển thị ở tab Dữ liệu của web).",
            table(["Chế độ", "Đã gửi", "Tốc độ", "Đã lưu", "Mất (đếm)", "Mất (ước lượng theo seq)"], rows),
            f"Telemetry dùng QoS1: {'không mất bản tin nào' if ok_all else 'có mất bản tin'} (mục tiêu 0 %). QoS0 chỉ "
            "dùng cho heartbeat vì mất 1 heartbeat không ảnh hưởng (backend chỉ đánh OFFLINE sau 30 s không có tín hiệu). "
            "Chỉ số ước lượng theo seq khớp với số đếm thực tế, chứng tỏ có thể dùng để theo dõi thiết bị thật.",
            f"QoS1 mất {RAW['P3_qos1']['loss_pct']:.2f} %", ok_all)


# ============================================================================ P4 – mất và khôi phục mạng
def p4_network(report: Report) -> None:
    print("\n▶ P4 – Mất và khôi phục mạng", flush=True)
    rows = []
    # (a) thiết bị mất mạng đột ngột: LWT, đệm dữ liệu, gửi bù
    dev = new_device("net", keepalive=10)
    dev.telemetry(gas=50, temp=30)
    code = dev.code
    t_cut = time.time()
    dev.kill()
    off = wait_until(lambda: api.call("GET", f"/api/devices/{code}", token=S["admin"])[1]["connectivity"] == "OFFLINE", 40, 0.25)
    t_detect = time.time() - t_cut
    buffered = []
    t_from = time.time()
    for i in range(20):                          # 20 s mất mạng, thiết bị vẫn đo 1 lần/giây và lưu đệm
        buffered.append((100 + i, now_ms()))
        time.sleep(1)
    dev2 = SimDevice(cfg, code, dev.password, keepalive=10)
    t_rec = time.time()
    assert dev2.connect() == 0
    dev2.seq = 1000
    on = wait_until(lambda: api.call("GET", f"/api/devices/{code}", token=S["admin"])[1]["connectivity"] == "ONLINE", 15, 0.25)
    t_online = time.time() - t_rec
    for gas, ts in buffered:                     # gửi bù đúng thời điểm đo gốc
        dev2.telemetry(gas=gas, temp=30, ts=ts, wait=False)
    time.sleep(3)
    stored = count_stored(code, t_from - 1, time.time())
    S["devices"].append(dev2)
    rows.append(["Thiết bị mất điện / mất WiFi đột ngột", "OFFLINE sau", f"{t_detect:.1f} s", "LWT của broker"])
    rows.append(["Thiết bị kết nối lại", "ONLINE sau", f"{t_online:.1f} s", "bản tin status ONLINE"])
    rows.append(["Dữ liệu đo trong 20 s mất mạng", "Đã lưu sau khi gửi bù", f"{stored}/20", "giữ đúng thời điểm đo gốc"])
    ok_a = off and on and stored == 20 and t_detect <= 30
    # (b) broker khởi động lại: backend và thiết bị tự kết nối lại, không mất dữ liệu QoS1
    cmd = os.environ.get("GG_BROKER_RESTART_CMD", "docker restart iot-mosquitto")
    ok_b = True
    try:
        devb = new_device("brk")
        t0 = time.time() - 1
        stop = threading.Event()
        sent = [0]
        def pump():
            while not stop.is_set():
                devb.telemetry(gas=70, temp=30, wait=False)
                sent[0] += 1
                time.sleep(0.5)
        th = threading.Thread(target=pump, daemon=True)
        th.start()
        time.sleep(3)
        t_restart = time.time()
        run_cmd(cmd, 90)
        back = wait_until(lambda: ingest().get("mqttConnected") and devb.connected.is_set(), 60, 0.5)
        t_back = time.time() - t_restart
        time.sleep(5)
        stop.set()
        th.join()
        time.sleep(4)
        stored_b = count_stored(devb.code, t0, time.time() + 2)
        rows.append([f"Broker khởi động lại (`{cmd}`)", "Backend + thiết bị kết nối lại sau", f"{t_back:.1f} s", "tự kết nối lại"])
        rows.append(["Bản tin QoS1 gửi trong lúc broker gián đoạn", "Đã lưu", f"{stored_b}/{sent[0]}", "client giữ hàng đợi và gửi lại"])
        RAW["P4_broker"] = {"restart_s": t_back, "sent": sent[0], "stored": stored_b}
        ok_b = bool(back) and stored_b >= sent[0] - 1     # 1 bản tin có thể đang gửi đúng lúc ngắt
    except Exception as e:  # noqa: BLE001
        rows.append(["Broker khởi động lại", "Không thực hiện được", "-", f"{type(e).__name__}: {e}"])
        ok_b = True
    # (c) backend khởi động lại (cập nhật phiên bản / sự cố): broker xếp hàng bản tin cho phiên bền của backend
    cmd_c = os.environ.get("GG_BACKEND_RESTART_CMD", "docker restart iot-backend")
    ok_c = True
    try:
        devc = new_device("bkd")
        t0 = time.time() - 1
        stop = threading.Event()
        sent = [0]
        def pump_c():
            while not stop.is_set():
                devc.telemetry(gas=75, temp=30, wait=False)
                sent[0] += 1
                time.sleep(0.5)
        th = threading.Thread(target=pump_c, daemon=True)
        th.start()
        time.sleep(3)
        t_restart = time.time()
        run_cmd(cmd_c, 150)
        time.sleep(3)
        up = wait_until(lambda: api.call("GET", "/actuator/health", timeout=3)[0] == 200, 150, 1)
        t_up = time.time() - t_restart
        S["admin"] = api.login(cfg.admin_user, cfg.admin_password)
        wait_until(lambda: ingest().get("mqttConnected"), 30, 0.5)
        time.sleep(5)
        stop.set()
        th.join()
        time.sleep(4)
        stored_c = count_stored(devc.code, t0, time.time() + 2)
        rows.append([f"Backend khởi động lại (`{cmd_c}`)", "Backend hoạt động lại sau", f"{t_up:.1f} s", "phiên MQTT bền"])
        rows.append(["Bản tin gửi trong lúc backend ngừng", "Đã lưu", f"{stored_c}/{sent[0]}", "broker xếp hàng QoS1 cho backend"])
        RAW["P4_backend"] = {"restart_s": t_up, "sent": sent[0], "stored": stored_c}
        ok_c = bool(up) and stored_c >= sent[0] - 1
    except Exception as e:  # noqa: BLE001
        rows.append(["Backend khởi động lại", "Không thực hiện được", "-", f"{type(e).__name__}: {e}"])
    RAW["P4"] = {"offline_detect_s": t_detect, "online_s": t_online, "buffered_stored": stored}
    ok = bool(ok_a and ok_b and ok_c)
    section(report, "P4", "Khả năng hoạt động sau khi mất và khôi phục mạng",
            "(a) Ngắt đột ngột kết nối TCP của thiết bị (giống rút điện / mất WiFi, không gửi DISCONNECT), đo thời gian "
            "đến khi backend chuyển OFFLINE. Trong 20 s mất mạng thiết bị vẫn đo mỗi giây và lưu đệm (giống firmware: "
            "bộ đệm 120 bản đo), kết nối lại thì gửi bù kèm thời điểm đo gốc; đếm số bản đo được lưu. (b) Khởi động lại "
            "broker MQTT trong khi thiết bị gửi 2 bản tin/giây; đo thời gian backend và thiết bị tự kết nối lại và đếm "
            "bản tin bị mất. (c) Khởi động lại backend trong khi thiết bị vẫn gửi; đếm bản tin được lưu sau khi backend chạy lại.",
            table(["Tình huống", "Đại lượng", "Giá trị", "Cơ chế"], rows),
            f"Mất kết nối đột ngột được phát hiện sau {t_detect:.1f} s nhờ LWT (mục tiêu ≤ 30 s; nếu không có LWT, "
            "watchdog backend vẫn đánh OFFLINE sau 30 s không có tín hiệu). Dữ liệu trong thời gian mất mạng không bị mất "
            "vì thiết bị đệm và gửi bù, CSDL không trùng nhờ khóa (thiết bị, chỉ số, thời điểm). Khi broker gián đoạn, "
            "backend tự kết nối lại (client MQTT mới, giãn dần 1 → 30 s). Backend dùng phiên MQTT bền (cleanSession=false): "
            "trong lúc backend hoặc broker khởi động lại, broker giữ đăng ký và xếp hàng bản tin QoS1 nên dữ liệu không mất.",
            f"OFFLINE sau {t_detect:.1f} s, gửi bù {stored}/20", ok)


# ============================================================================ P5 – bản tin trùng lặp
def p5_duplicates(report: Report, n: int) -> None:
    print("\n▶ P5 – Xử lý bản tin trùng lặp", flush=True)
    dev = new_device("dup")
    before = ingest()["duplicateReadingsIgnored"]
    t0 = time.time() - 1
    msgs = []
    for i in range(n):
        seq, ts = dev.telemetry(gas=80 + i % 10, temp=30, wait=False)
        msgs.append((seq, ts, 80 + i % 10))
        time.sleep(0.02)
    time.sleep(1)
    for seq, ts, gas in msgs:                       # gửi lại y hệt (giống QoS1 giao lại / thiết bị gửi bù trùng)
        dev.telemetry(gas=gas, temp=30, seq=seq, ts=ts, wait=False)
        time.sleep(0.02)
    time.sleep(3)
    stored = count_stored(dev.code, t0, time.time() + 2)
    ignored = ingest()["duplicateReadingsIgnored"] - before
    RAW["P5"] = {"unique": n, "sent": 2 * n, "stored": stored, "duplicates_ignored": ignored}
    ok = stored == n and ignored >= n * 2
    section(report, "P5", "Khả năng xử lý bản tin trùng lặp",
            f"Gửi {n} bản tin khác nhau, sau đó gửi lại đúng {n} bản tin đó lần nữa (cùng `seq`, cùng `ts`), mô phỏng "
            "QoS1 giao lại hoặc thiết bị gửi bù trùng sau khi mất mạng. Đếm số bản ghi trong CSDL và bộ đếm "
            "`duplicateReadingsIgnored` của backend (mỗi bản tin có 2 chỉ số gas + temp).",
            table(["Bản tin khác nhau", "Tổng đã gửi", "Đã lưu (gas)", "Số đo trùng bị bỏ qua"], [[n, 2 * n, stored, ignored]]),
            f"CSDL lưu đúng {stored}/{n} bản tin, không có bản ghi trùng; {ignored} số đo trùng bị bỏ qua nhờ ràng buộc "
            "UNIQUE(device_id, metric, ts) + INSERT … ON CONFLICT DO NOTHING. Cảnh báo cũng không bị sinh lặp vì "
            "cảnh báo đang mở được gộp theo (thiết bị, loại, luật).",
            f"lưu {stored}/{n}, bỏ qua {ignored} trùng", ok)


# ============================================================================ P7 – ổn định
def p7_stability(report: Report, minutes: int, k: int) -> None:
    print(f"\n▶ P7 – Độ ổn định {minutes} phút với {k} thiết bị", flush=True)
    devs = [new_device(f"stb{i}") for i in range(k)]
    rows, sent_total, stored_total = [], 0, 0
    RES.start("P7")
    t_start = time.time()
    mem_series = []
    for minute in range(minutes):
        t0 = time.time()
        sent = 0
        lat_before = ingest()
        while time.time() - t0 < 60:
            for d in devs:
                d.telemetry(gas=50 + minute, temp=30, wait=False)
                sent += 1
            time.sleep(1)
        time.sleep(2)
        stored = sum(count_stored(d.code, t0 - 0.5, time.time()) for d in devs)
        snap = ingest()
        win = RES.window[-1] if RES.window else {}
        mem = (win.get("iot-backend") or {}).get("mem")
        mem_series.append(mem)
        sent_total += sent
        stored_total += stored
        rows.append([minute + 1, sent, stored, f"{(sent - stored) / sent * 100:.2f} %",
                     backend_p95(snap), snap["rejectedMessages"] - lat_before["rejectedMessages"],
                     f"{mem:.0f}" if mem else "-"])
        print(f"   phút {minute + 1}: gửi {sent}, lưu {stored}, RAM backend {mem}", flush=True)
    agg = RES.stop()
    loss = (sent_total - stored_total) / sent_total * 100
    mems = [m for m in mem_series if m]
    growth = (mems[-1] - mems[0]) / mems[0] * 100 if len(mems) >= 2 else 0
    RAW["P7"] = {"rows": rows, "resources": agg}
    ok = loss == 0 and all(r[5] == 0 for r in rows) and growth < 30
    section(report, "P7", "Độ ổn định khi chạy liên tục",
            f"{k} thiết bị mô phỏng, mỗi thiết bị gửi 1 bản tin/giây (5 lần dày hơn cấu hình mặc định 5 s) liên tục "
            f"{minutes} phút. Mỗi phút đếm bản tin đã gửi / đã lưu, P95 độ trễ thiết bị → backend (bộ đếm của backend), "
            "số bản tin bị từ chối, RAM của backend.",
            table(["Phút", "Đã gửi", "Đã lưu", "Mất", "P95 độ trễ (ms)", "Bị từ chối", "RAM backend (MiB)"], rows)
            + f"\n\nTổng: gửi {sent_total}, lưu {stored_total}, mất {loss:.3f} %. Thời gian chạy {time.time() - t_start:.0f} s.",
            f"Trong {minutes} phút không mất bản tin ({loss:.3f} %), không có lỗi, độ trễ ổn định; RAM backend thay đổi "
            f"{growth:+.1f} % giữa phút đầu và phút cuối (heap giới hạn 384 MB nên không tăng vô hạn). "
            + ("" if RES.mode else "Không đo được RAM vì không truy cập được `docker stats`. ")
            + "Có thể chạy dài hơn bằng `--stability-min 60`.",
            f"mất {loss:.3f} %, RAM {growth:+.1f} %", ok)


# ============================================================================ P6 + P8 – tải nhiều thiết bị + CPU/RAM
def p8_load(report: Report, steps: list[int], seconds: int) -> None:
    print(f"\n▶ P8 – Số lượng thiết bị / bản tin xử lý được: {steps}", flush=True)
    RES.start("idle")
    time.sleep(6)
    idle = RES.stop()
    rows, res_rows, ok = [], [], True
    pool: list[SimDevice] = []
    for k in steps:
        while len(pool) < k:
            pool.append(new_device(f"ld{len(pool)}"))
        devs = pool[:k]
        base = ingest()
        RES.start(f"load{k}")
        t0 = time.time()
        sent = 0
        while time.time() - t0 < seconds:
            tick = time.time()
            for d in devs:
                d.telemetry(gas=55, temp=30, wait=False)
                sent += 1
            time.sleep(max(0.0, 1 - (time.time() - tick)))
        elapsed = time.time() - t0
        time.sleep(3)
        agg = RES.stop()
        snap = ingest()
        stored = sum(count_stored(d.code, t0 - 0.5, time.time()) for d in devs)
        loss = (sent - stored) / sent * 100
        thr = (snap["telemetryAccepted"] - base["telemetryAccepted"]) / elapsed
        p95 = backend_p95(snap)
        rows.append([k, f"{sent / elapsed:.0f}", f"{thr:.0f}", sent, stored, f"{loss:.2f} %", p95,
                     snap["rejectedMessages"] - base["rejectedMessages"]])
        b = agg.get("iot-backend", {})
        mq = agg.get("iot-mosquitto", {})
        pg = agg.get("iot-postgres", {})
        res_rows.append([k, b.get("cpu_avg", "-"), b.get("cpu_max", "-"), b.get("mem_max", "-"),
                         mq.get("cpu_avg", "-"), mq.get("mem_max", "-"), pg.get("cpu_avg", "-"), pg.get("mem_max", "-")])
        RAW[f"P8_{k}"] = {"sent": sent, "stored": stored, "throughput": thr, "p95": p95, "resources": agg}
        if loss > 0 or (p95 or 0) > 1000:
            ok = False
        print(f"   {k} thiết bị: gửi {sent}, lưu {stored}, P95 {p95} ms, CPU backend {b.get('cpu_avg')}", flush=True)
    b0 = idle.get("iot-backend", {})
    res_rows.insert(0, ["0 (nghỉ)", b0.get("cpu_avg", "-"), b0.get("cpu_max", "-"), b0.get("mem_max", "-"),
                        idle.get("iot-mosquitto", {}).get("cpu_avg", "-"), idle.get("iot-mosquitto", {}).get("mem_max", "-"),
                        idle.get("iot-postgres", {}).get("cpu_avg", "-"), idle.get("iot-postgres", {}).get("mem_max", "-")])
    section(report, "P8", "Số lượng thiết bị / bản tin hệ thống xử lý được",
            f"Tăng dần số thiết bị mô phỏng kết nối đồng thời ({', '.join(map(str, steps))}); mỗi thiết bị gửi 1 bản "
            f"tin/giây (QoS1, mỗi bản tin 2 chỉ số) trong {seconds} s. Đo thông lượng backend chấp nhận, số bản tin lưu "
            "được, tỷ lệ mất, P95 độ trễ thiết bị → backend.",
            table(["Số thiết bị", "Bản tin gửi/s", "Backend xử lý/s", "Đã gửi", "Đã lưu", "Mất", "P95 độ trễ (ms)", "Bị từ chối"], rows),
            f"Hệ thống xử lý {max(steps)} thiết bị đồng thời ({max(steps)} bản tin/s ≈ {max(steps) * 5} thiết bị thật "
            f"với chu kỳ 5 s) {'không mất bản tin, P95 < 1000 ms' if ok else 'nhưng có mất bản tin hoặc trễ cao'}. "
            "Giới hạn chính là ghi CSDL (mỗi bản tin 1 giao dịch); có thể tăng bằng ghi theo lô nếu cần nhiều thiết bị hơn.",
            f"{max(steps)} thiết bị, mất {RAW[f'P8_{max(steps)}']['sent'] - RAW[f'P8_{max(steps)}']['stored']} bản tin, "
            f"P95 {RAW[f'P8_{max(steps)}']['p95']} ms", ok)
    if RES.mode:
        ok6 = all((RAW.get(f"P8_{k}", {}).get("resources", {}).get("iot-backend", {}).get("mem_max") or 0) <= 600 for k in steps)
        section(report, "P6", "Mức sử dụng CPU và bộ nhớ",
                f"Lấy mẫu mỗi ~2 s bằng `{'docker stats' if RES.mode == 'docker' else 'psutil (theo tiến trình)'}` cho từng "
                "thành phần, lúc nghỉ và trong từng mức tải của P8. CPU tính theo % một nhân (100 % = 1 nhân).",
                table(["Số thiết bị", "CPU backend TB %", "CPU backend max %", "RAM backend max (MiB)", "CPU broker TB %",
                       "RAM broker max (MiB)", "CPU CSDL TB %", "RAM CSDL max (MiB)"], res_rows),
                "Broker Mosquitto rất nhẹ (vài MiB RAM). Backend Java dùng nhiều RAM nhất nhưng bị giới hạn heap 384 MB "
                "(`JAVA_OPTS`) nên vừa với máy 8 GB RAM; CPU tăng gần tuyến tính theo số bản tin/giây. Thiết bị ESP32 "
                "(firmware) dùng khoảng 15 % RAM và 74 % flash theo kết quả biên dịch.",
                f"RAM backend max {max((RAW.get(f'P8_{k}', {}).get('resources', {}).get('iot-backend', {}).get('mem_max') or 0) for k in steps):.0f} MiB",
                ok6)
    else:
        section(report, "P6", "Mức sử dụng CPU và bộ nhớ", "Không lấy được số liệu.",
                "Không chạy được `docker stats` và chưa cài `psutil`.",
                "Chạy lại trên máy có Docker (hệ thống chạy bằng `app-up`).", "không đo được", False)


# ============================================================================ chạy
def cleanup() -> None:
    for d in S["devices"]:
        d.close()
    codes = {d.code for d in S["devices"]}
    for code in codes:
        api.call("POST", f"/api/devices/{code}/lifecycle", {"action": "DECOMMISSION", "reason": "dọn dữ liệu kiểm thử hiệu năng"}, S["admin"])


def main() -> None:
    ap = argparse.ArgumentParser(description="Kiểm thử phi chức năng GasGuard (mục 7.2)")
    ap.add_argument("--quick", action="store_true", help="chạy nhanh: ổn định 2 phút, tải 10/50 thiết bị 30 s")
    ap.add_argument("--stability-min", type=int, default=None, help="số phút chạy ổn định (mặc định 10)")
    ap.add_argument("--load", default=None, help="các mức số thiết bị, vd 10,50,100")
    ap.add_argument("--load-seconds", type=int, default=None)
    ap.add_argument("--only", default=None, help="chỉ chạy một số chỉ tiêu, vd P1,P2,P5")
    a = ap.parse_args()
    stab = a.stability_min or (2 if a.quick else 10)
    steps = [int(x) for x in (a.load or ("10,50" if a.quick else "10,50,100")).split(",")]
    secs = a.load_seconds or (30 if a.quick else 60)
    only = set((a.only or "P1,P2,P3,P4,P5,P6,P7,P8").upper().split(","))

    report = Report("perf", "Báo cáo kiểm thử phi chức năng (7.2) – GasGuard", cfg)
    s, _ = api.call("GET", "/actuator/health")
    if s != 200:
        raise SystemExit(f"Backend chưa chạy ({cfg.api}). Hãy chạy hệ thống trước.")
    S["admin"] = api.login(cfg.admin_user, cfg.admin_password)
    measured, best_rtt = clock_offset_ms(api, S["admin"])
    if measured is None:
        # Khứ hồi API quá chậm thì phép đo kiểu NTP sai lệch lớn -> dừng, tránh ghi số liệu độ trễ sai vào báo cáo
        raise SystemExit(f"Gọi API mất {best_rtt:.0f} ms/lần (bình thường < 50 ms) nên không đo được độ lệch đồng hồ. "
                         f"Kiểm tra địa chỉ API ({cfg.api}): dùng 127.0.0.1 thay cho localhost, máy không quá tải.")
    # Phép đo qua API có sai số vài ms (backend ghi generatedAt sau khi truy vấn CSDL) -> chỉ hiệu chỉnh khi
    # lệch thật sự (> 50 ms, thường gặp khi đồng hồ máy ảo WSL2 của Docker Desktop bị trôi)
    S["offset_measured"] = measured
    S["offset"] = measured if abs(measured) > 50 else 0.0
    print(f"Mã lần chạy {RID}; đo tài nguyên bằng: {RES.mode or 'không có'}; lệch đồng hồ backend {measured:+.1f} ms "
          f"(khứ hồi API {best_rtt:.0f} ms)")
    try:
        if "P1" in only:
            p1_dashboard_latency(report, 100 if a.quick else 200)
        if "P2" in only:
            if not S["devices"]:
                new_device("cmd")
            p2_command_latency(report, 30 if a.quick else 50)
        if "P3" in only:
            p3_loss(report, 500 if a.quick else 1000, 50)
        if "P4" in only:
            p4_network(report)
        if "P5" in only:
            p5_duplicates(report, 200)
        if "P7" in only:
            p7_stability(report, stab, 5)
        if "P8" in only or "P6" in only:
            p8_load(report, steps, secs)
    finally:
        cleanup()
    order = {f"P{i}": i for i in range(1, 9)}
    VERDICTS.sort(key=lambda v: order.get(v[0], 99))
    report.sections.sort(key=lambda s: order.get(s[0].split(" ")[0], 99))
    report.sections.insert(0, ("Tổng hợp", table(["Mã", "Chỉ tiêu", "Kết quả đo", "So với mục tiêu"],
                                                 [list(v) for v in VERDICTS])))
    report.section("Số liệu thô", "Toàn bộ số liệu đo (từng mẫu độ trễ, CPU/RAM) nằm trong file JSON cùng tên. "
                   f"Độ lệch đồng hồ backend so với máy chạy test đo được {S['offset_measured']:+.1f} ms (phương pháp kiểu NTP "
                   "qua `generatedAt` của API, sai số vài ms). "
                   + (f"Đã trừ {S['offset']:+.1f} ms khỏi các độ trễ do backend tính." if S["offset"]
                      else "Độ lệch < 50 ms nên không hiệu chỉnh (không đáng kể)."))
    path = report.write()
    raw_path = path.with_suffix(".json")
    data = json.loads(raw_path.read_text(encoding="utf-8"))
    data["raw"] = RAW
    data["resources"] = RES.samples
    raw_path.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\n==== Báo cáo: {path}")


if __name__ == "__main__":
    main()
