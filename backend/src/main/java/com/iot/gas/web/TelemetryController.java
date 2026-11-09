package com.iot.gas.web;

import com.iot.gas.data.TelemetryQueryService;
import com.iot.gas.data.TimeRange;
import com.iot.gas.security.AuthUser;
import com.iot.gas.security.CurrentUser;
import com.iot.gas.web.dto.*;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Thời gian from/to theo ISO-8601: 2026-11-06T03:00:00Z | 2026-11-06T10:00:00+07:00 | 2026-11-06T10:00 (giờ VN).
 * Bỏ trống: to = bây giờ, from = to - (1 giờ với dữ liệu thô, 24 giờ với gộp/thống kê/CSV).
 */
@RestController
@RequestMapping("/api/devices/{code}/telemetry")
@RequiredArgsConstructor
public class TelemetryController {

    private final TelemetryQueryService query;

    /** Số đo mới nhất của từng chỉ số. */
    @GetMapping("/latest")
    public Map<String, LatestReadingDto> latest(@PathVariable String code) {
        return query.latest(CurrentUser.require(), code);
    }

    /** Dữ liệu thô cho bảng, mới nhất trước. metric: gas | temp | all */
    @GetMapping
    public TelemetryPageDto raw(@PathVariable String code,
                                @RequestParam(required = false) String metric,
                                @RequestParam(required = false) String from,
                                @RequestParam(required = false) String to,
                                @RequestParam(defaultValue = "1000") int limit) {
        return query.raw(CurrentUser.require(), code, metric, from, to, limit);
    }

    /** Dữ liệu gộp cho biểu đồ. bucket: auto | 1m | 5m | 15m | 30m | 1h | 6h | 1d */
    @GetMapping("/series")
    public SeriesDto series(@PathVariable String code,
                            @RequestParam(required = false) String metric,
                            @RequestParam(required = false) String from,
                            @RequestParam(required = false) String to,
                            @RequestParam(defaultValue = "auto") String bucket) {
        return query.series(CurrentUser.require(), code, metric, from, to, bucket);
    }

    /** Thống kê min / max / trung bình / p95 / độ trễ / ước lượng tỉ lệ mất bản tin. */
    @GetMapping("/stats")
    public TelemetryStatsDto stats(@PathVariable String code,
                                   @RequestParam(required = false) String from,
                                   @RequestParam(required = false) String to) {
        return query.stats(CurrentUser.require(), code, from, to);
    }

    /** Xuất CSV (tối đa 31 ngày). Mở được bằng Excel (UTF-8 có BOM). */
    @GetMapping(value = "/export.csv", produces = "text/csv")
    public void exportCsv(@PathVariable String code,
                          @RequestParam(required = false) String metric,
                          @RequestParam(required = false) String from,
                          @RequestParam(required = false) String to,
                          HttpServletResponse response) throws IOException {
        AuthUser user = CurrentUser.require();
        TimeRange range = query.checkExport(user, code, metric, from, to);   // lỗi quyền/tham số -> JSON 4xx
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(TimeRange.LOCAL_ZONE);
        String filename = "telemetry_" + code + "_" + f.format(range.from()) + "_" + f.format(range.to()) + ".csv";
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        Writer out = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
        query.exportCsv(user, code, metric, from, to, out);
    }
}
