"""
Gộp kết quả kiểm thử thành docs/test-report.md (chương "Kiểm thử và đánh giá" của báo cáo).

    python tests/make_report.py

Kiểm thử chức năng/bảo mật: lần chạy e2e MỚI NHẤT. Kiểm thử phi chức năng: mỗi chỉ tiêu P1–P8 lấy từ lần đo
GẦN NHẤT có chỉ tiêu đó (chạy lại một phần, vd. `run_perf.py --only P6,P8`, không làm mất các chỉ tiêu khác).
Kèm bảng kiểm thử phần cứng docs/hardware-test-results.md. Số liệu là số đo thật của nhóm, không nhập tay.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPORTS = ROOT / "tests" / "reports"
OUT = ROOT / "docs" / "test-report.md"


def latest(kind: str) -> tuple[Path, dict] | tuple[None, None]:
    files = sorted(REPORTS.glob(f"{kind}-2*.json"))
    if not files:
        return None, None
    return files[-1], json.loads(files[-1].read_text(encoding="utf-8"))


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def when(run: dict) -> str:
    return run["started"][:16].replace("T", " ")


def perf_by_metric() -> dict[str, dict]:
    """P1..P8 -> {path, run, row (dòng bảng Tổng hợp), section}: lần đo gần nhất có chỉ tiêu đó."""
    chosen: dict[str, dict] = {}
    for path in sorted(REPORTS.glob("perf-2*.json")):          # tên file theo thời gian -> lần sau ghi đè lần trước
        run = json.loads(path.read_text(encoding="utf-8"))
        summary = next((s for s in run["sections"] if s["title"] == "Tổng hợp"), None)
        rows = {}
        for line in (summary["markdown"] if summary else "").splitlines():
            m = re.match(r"\| (P\d) \|", line)
            if m:
                rows[m.group(1)] = line.rstrip()
        for sec in run["sections"]:
            m = re.match(r"(P\d) ", sec["title"])
            if m:
                chosen[m.group(1)] = {"path": path, "run": run, "row": rows.get(m.group(1)), "section": sec}
    return dict(sorted(chosen.items()))


def main() -> None:
    md = ["# Báo cáo kiểm thử và đánh giá – GasGuard", "",
          "Tài liệu tự sinh bởi `python tests/make_report.py` từ kết quả chạy thật. Kế hoạch kiểm thử, "
          "phương pháp và mục tiêu: [test-plan.md](test-plan.md).", ""]

    e2e_path, e2e = latest("e2e")
    md.append("## 1. Kiểm thử chức năng (7.1) và bảo mật (4.5)")
    md.append("")
    if e2e:
        cases = e2e["cases"]
        ok = sum(c["status"] == "PASS" for c in cases)
        md += [f"Lần chạy: {e2e['started'][:19].replace('T', ' ')} – **{ok}/{len(cases)} ca đạt**. "
               f"Chi tiết từng bước: [`{e2e_path.with_suffix('.md').relative_to(ROOT).as_posix()}`](../{e2e_path.with_suffix('.md').relative_to(ROOT).as_posix()}).", "",
               "Môi trường: " + ", ".join(f"{k} `{v}`" for k, v in e2e["env"].items()), "",
               "| Mã | Ca kiểm thử | Yêu cầu | Số bước kiểm tra | Kết quả |", "|---|---|---|---|---|"]
        for c in cases:
            res = {"PASS": "✅ Đạt", "FAIL": "❌ Không đạt", "SKIP": "⏭ Bỏ qua"}[c["status"]]
            md.append(f"| {c['id']} | {c['name']} | {c['req']} | {len(c['steps'])} | {res} |")
        sec = [c for c in cases if c["id"].startswith("S")]
        md += ["", f"Đề bài yêu cầu tối thiểu 2 kiểm thử bảo mật; nhóm thực hiện **{len(sec)}** ca (S1–S{len(sec)}), "
               f"gồm đủ 5 ví dụ trong mục 4.5. Bằng chứng tiêu biểu:", ""]
        for c in sec:
            first_fail = next((s for s in c["steps"] if not s["ok"]), None)
            sample = (first_fail or c["steps"][0])["text"] if c["steps"] else ""
            md.append(f"- **{c['id']}** {c['name']}: {sample}")
    else:
        md.append("_Chưa có kết quả. Chạy `python tests/e2e/run_e2e.py`._")
    md.append("")

    perf = perf_by_metric()
    md.append("## 2. Kiểm thử phi chức năng (7.2)")
    md.append("")
    if perf:
        runs = {}
        for code, item in perf.items():
            runs.setdefault(item["path"], (item["run"], []))[1].append(code)
        md += ["Mỗi chỉ tiêu lấy từ lần đo gần nhất có chỉ tiêu đó. Số liệu thô (từng mẫu) trong file JSON của lần đo:", "",
               "| Lần đo | Chỉ tiêu | Số liệu thô |", "|---|---|---|"]
        for path, (run, codes) in runs.items():
            md.append(f"| {when(run)} | {', '.join(codes)} | [`{rel(path)}`](../{rel(path)}) |")
        md += ["", "### Tổng hợp", "", "| Mã | Chỉ tiêu | Kết quả đo | So với mục tiêu | Lần đo |", "|---|---|---|---|---|"]
        for code, item in perf.items():
            if item["row"]:
                md.append(f"{item['row']} {when(item['run'])} |")
        md.append("")
        for code, item in perf.items():
            md += [f"### {item['section']['title']}", "", item["section"]["markdown"], ""]
        for path, (run, codes) in runs.items():
            raw = next((s for s in run["sections"] if s["title"] == "Số liệu thô"), None)
            note = re.search(r"Độ lệch đồng hồ[^\n]*", raw["markdown"]) if raw else None
            if note:
                md.append(f"- Lần đo {when(run)} ({', '.join(codes)}): {note.group(0)}")
        md.append("")
    else:
        md.append("_Chưa có kết quả. Chạy `python tests/perf/run_perf.py`._")
        md.append("")

    md.append("## 3. Kiểm thử với thiết bị thật (ESP32)")
    md.append("")
    hw = ROOT / "docs" / "hardware-test-results.md"
    if hw.exists():
        body = hw.read_text(encoding="utf-8").split("\n", 1)[1].strip()
        md += [body, ""]
    md += ["## 4. Đánh giá chung", "",
           "- Các quy trình bắt buộc của mục 7.1 chạy đầu-cuối với thiết bị mô phỏng đúng giao thức và với ESP32 thật.",
           "- Bảo mật được kiểm tra ở 3 lớp: broker (ACL theo thiết bị, tài khoản riêng), API (JWT, phân quyền ở backend, "
           "kiểm tra dữ liệu vào) và lưu trữ (BCrypt, audit log).",
           "- Đường truyền qua LAN được mã hóa TLS (MQTT qua TLS, HTTPS); ca S9 kiểm tra kết nối không mã hóa và chứng chỉ giả bị từ chối.",
           "- Hạn chế: CA do nhóm tự tạo (máy chưa cài CA sẽ thấy cảnh báo chứng chỉ); "
           "số liệu tải đo trên một laptop, chưa đo trên máy chủ riêng.", ""]
    OUT.write_text("\n".join(md) + "\n", encoding="utf-8")
    print(f"Đã tạo {OUT}")


if __name__ == "__main__":
    main()
