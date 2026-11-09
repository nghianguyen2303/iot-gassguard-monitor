package com.iot.gas.data;

import com.iot.gas.domain.Device;
import com.iot.gas.domain.enums.AuditResult;
import com.iot.gas.security.AuthUser;
import com.iot.gas.service.AuditService;
import com.iot.gas.service.DeviceService;
import com.iot.gas.web.ApiException;
import com.iot.gas.web.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Tra cứu dữ liệu telemetry (mục 4.4c). Mọi hàm đều kiểm tra quyền xem thiết bị trước (mục 4.5).
 * Truy vấn gộp/thống kê chạy bằng SQL trong PostgreSQL (dùng chỉ mục (device_id, metric, ts)),
 * không kéo toàn bộ dữ liệu thô lên Java.
 */
@Service
@RequiredArgsConstructor
public class TelemetryQueryService {

    static final Set<String> METRICS = Set.of("gas", "temp");
    static final Duration RAW_MAX_SPAN = Duration.ofDays(31);
    static final Duration AGG_MAX_SPAN = Duration.ofDays(366);
    static final int RAW_MAX_LIMIT = 20_000;
    /** Biểu đồ đọc tốt với ~300 điểm; bucket "auto" chọn khoảng gộp nhỏ nhất giữ số điểm ≤ 360. */
    static final int TARGET_POINTS = 360;

    /** Mốc chia bucket theo giờ Việt Nam: bucket "1d" bắt đầu lúc 00:00 (+07:00) chứ không phải 07:00. */
    private static final String BUCKET_ORIGIN = "2000-01-01 00:00:00+07";

    private static final LinkedHashMap<String, Duration> BUCKETS = new LinkedHashMap<>();

    static {
        BUCKETS.put("1m", Duration.ofMinutes(1));
        BUCKETS.put("5m", Duration.ofMinutes(5));
        BUCKETS.put("15m", Duration.ofMinutes(15));
        BUCKETS.put("30m", Duration.ofMinutes(30));
        BUCKETS.put("1h", Duration.ofHours(1));
        BUCKETS.put("6h", Duration.ofHours(6));
        BUCKETS.put("1d", Duration.ofDays(1));
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final DeviceService deviceService;
    private final AuditService audit;

    // ====================================================================== Dữ liệu hiện tại
    public Map<String, LatestReadingDto> latest(AuthUser user, String code) {
        Device d = deviceService.loadForView(user, code);
        return latestByDevice(List.of(d.getId())).getOrDefault(d.getId(), Map.of());
    }

    /** Số đo mới nhất của mỗi chỉ số cho nhiều thiết bị (1 truy vấn, dùng chỉ mục). */
    public Map<Long, Map<String, LatestReadingDto>> latestByDevice(Collection<Long> deviceIds) {
        Map<Long, Map<String, LatestReadingDto>> out = new HashMap<>();
        if (deviceIds.isEmpty()) {
            return out;
        }
        String sql = """
                SELECT d.id AS device_id, m.metric, t.value, t.unit, t.ts
                FROM unnest(CAST(:ids AS bigint[])) AS d(id)
                CROSS JOIN (VALUES ('gas'), ('temp')) AS m(metric)
                CROSS JOIN LATERAL (
                    SELECT value, unit, ts FROM telemetry
                    WHERE device_id = d.id AND metric = m.metric
                    ORDER BY ts DESC LIMIT 1) t
                """;
        jdbc.query(sql, new MapSqlParameterSource("ids", deviceIds.toArray(new Long[0])), rs -> {
            out.computeIfAbsent(rs.getLong("device_id"), k -> new LinkedHashMap<>())
                    .put(rs.getString("metric"), new LatestReadingDto(rs.getString("metric"),
                            rs.getDouble("value"), rs.getString("unit"), instant(rs, "ts")));
        });
        return out;
    }

    // ====================================================================== Dữ liệu thô (bảng)
    public TelemetryPageDto raw(AuthUser user, String code, String metric, String from, String to, int limit) {
        Device d = deviceService.loadForView(user, code);
        TimeRange range = TimeRange.parse(from, to, Duration.ofHours(1), RAW_MAX_SPAN);
        String m = checkMetric(metric);
        int lim = Math.min(Math.max(limit, 1), RAW_MAX_LIMIT);
        MapSqlParameterSource p = params(d, range).addValue("limit", lim + 1);
        String sql = "SELECT ts, metric, value, unit, seq, received_at FROM telemetry "
                + "WHERE device_id = :deviceId AND ts >= :from AND ts < :to "
                + (m == null ? "" : "AND metric = :metric ")
                + "ORDER BY ts DESC, metric LIMIT :limit";
        if (m != null) {
            p.addValue("metric", m);
        }
        List<TelemetryPointDto> rows = jdbc.query(sql, p, (rs, i) -> {
            Instant ts = instant(rs, "ts");
            Instant rec = instant(rs, "received_at");
            long seq = rs.getLong("seq");
            return new TelemetryPointDto(ts, rs.getString("metric"), rs.getDouble("value"), rs.getString("unit"),
                    rs.wasNull() ? null : seq, rec, Duration.between(ts, rec).toMillis());
        });
        boolean truncated = rows.size() > lim;
        if (truncated) {
            rows = rows.subList(0, lim);
        }
        return new TelemetryPageDto(code, range.from(), range.to(), rows.size(), truncated, rows);
    }

    // ====================================================================== Dữ liệu gộp (biểu đồ)
    public SeriesDto series(AuthUser user, String code, String metric, String from, String to, String bucket) {
        Device d = deviceService.loadForView(user, code);
        TimeRange range = TimeRange.parse(from, to, Duration.ofHours(24), AGG_MAX_SPAN);
        String m = checkMetric(metric);
        String b = chooseBucket(bucket, range.span());
        MapSqlParameterSource p = params(d, range).addValue("bucket", intervalOf(BUCKETS.get(b)));
        String sql = "SELECT date_bin(CAST(:bucket AS interval), ts, TIMESTAMPTZ '" + BUCKET_ORIGIN + "') AS bucket, "
                + "metric, avg(value) AS avg, min(value) AS min, max(value) AS max, count(*) AS n "
                + "FROM telemetry WHERE device_id = :deviceId AND ts >= :from AND ts < :to "
                + (m == null ? "" : "AND metric = :metric ")
                + "GROUP BY 1, metric ORDER BY 1, metric";
        if (m != null) {
            p.addValue("metric", m);
        }
        List<SeriesPointDto> points = jdbc.query(sql, p, (rs, i) -> new SeriesPointDto(instant(rs, "bucket"),
                rs.getString("metric"), round(rs.getDouble("avg")), rs.getDouble("min"), rs.getDouble("max"),
                rs.getLong("n")));
        return new SeriesDto(code, range.from(), range.to(), b, points);
    }

    static String chooseBucket(String requested, Duration span) {
        if (requested != null && !requested.isBlank() && !"auto".equalsIgnoreCase(requested)) {
            if (!BUCKETS.containsKey(requested)) {
                throw ApiException.badRequest("BAD_BUCKET", "bucket phải là auto hoặc " + BUCKETS.keySet());
            }
            if (span.dividedBy(BUCKETS.get(requested)) > 5000) {
                throw ApiException.badRequest("TOO_MANY_POINTS", "Khoảng gộp quá nhỏ so với khoảng thời gian (> 5000 điểm)");
            }
            return requested;
        }
        for (Map.Entry<String, Duration> e : BUCKETS.entrySet()) {
            if (span.dividedBy(e.getValue()) <= TARGET_POINTS) {
                return e.getKey();
            }
        }
        return "1d";
    }

    // ====================================================================== Thống kê
    public TelemetryStatsDto stats(AuthUser user, String code, String from, String to) {
        Device d = deviceService.loadForView(user, code);
        TimeRange range = TimeRange.parse(from, to, Duration.ofHours(24), AGG_MAX_SPAN);
        String sql = """
                SELECT metric, min(unit) AS unit, count(*) AS n, min(value) AS min, max(value) AS max,
                       avg(value) AS avg,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY value) AS p95,
                       min(ts) AS first_ts, max(ts) AS last_ts,
                       avg(EXTRACT(EPOCH FROM (received_at - ts)) * 1000) AS latency
                FROM telemetry
                WHERE device_id = :deviceId AND ts >= :from AND ts < :to
                GROUP BY metric ORDER BY metric
                """;
        List<MetricStatsDto> metrics = jdbc.query(sql, params(d, range), (rs, i) -> new MetricStatsDto(
                rs.getString("metric"), rs.getString("unit"), rs.getLong("n"), rs.getDouble("min"),
                rs.getDouble("max"), round(rs.getDouble("avg")), round(rs.getDouble("p95")),
                instant(rs, "first_ts"), instant(rs, "last_ts"), round(rs.getDouble("latency"))));
        Long alerts = jdbc.queryForObject("SELECT count(*) FROM alerts WHERE device_id = :deviceId "
                + "AND created_at >= :from AND created_at < :to", params(d, range), Long.class);

        // Tỉ lệ mất bản tin (mục 7.2) tính bằng seq: thiết bị đánh số liên tục từng bản tin telemetry.
        // Trong 1 đoạn liên tục, số lẽ ra nhận = max(seq) - min(seq) + 1. seq giảm/lặp lại nghĩa là thiết bị
        // khởi động lại (đếm lại từ đầu) -> tách đoạn mới. Bản trùng đã bị DB loại nên không làm sai kết quả.
        String lossSql = """
                WITH s AS (
                    SELECT ts, seq,
                           CASE WHEN lag(seq) OVER w IS NULL OR seq <= lag(seq) OVER w THEN 1 ELSE 0 END AS brk
                    FROM telemetry
                    WHERE device_id = :deviceId AND metric = 'gas' AND seq IS NOT NULL
                      AND ts >= :from AND ts < :to
                    WINDOW w AS (ORDER BY ts)),
                g AS (SELECT seq, sum(brk) OVER (ORDER BY ts) AS grp FROM s)
                SELECT count(*) AS segments, coalesce(sum(n), 0) AS received, coalesce(sum(mx - mn + 1), 0) AS expected
                FROM (SELECT grp, count(*) AS n, min(seq) AS mn, max(seq) AS mx FROM g GROUP BY grp) x
                """;
        long[] r = jdbc.queryForObject(lossSql, params(d, range),
                (rs, i) -> new long[]{rs.getLong("segments"), rs.getLong("received"), rs.getLong("expected")});
        Long expected = null;
        Double loss = null;
        if (r != null && r[2] > 0) {
            expected = r[2];
            loss = round(Math.max(0, 1.0 - (double) r[1] / r[2]) * 100);
        }
        return new TelemetryStatsDto(code, range.from(), range.to(), metrics, alerts == null ? 0 : alerts, expected, loss);
    }

    // ====================================================================== Xuất CSV
    /** Ghi CSV trực tiếp ra response (stream từng dòng, không giữ toàn bộ trong bộ nhớ). */
    public void exportCsv(AuthUser user, String code, String metric, String from, String to, Writer out) {
        Device d = deviceService.loadForView(user, code);
        TimeRange range = TimeRange.parse(from, to, Duration.ofHours(24), RAW_MAX_SPAN);
        String m = checkMetric(metric);
        MapSqlParameterSource p = params(d, range);
        String sql = "SELECT ts, metric, value, unit, seq, received_at FROM telemetry "
                + "WHERE device_id = :deviceId AND ts >= :from AND ts < :to "
                + (m == null ? "" : "AND metric = :metric ") + "ORDER BY ts, metric";
        if (m != null) {
            p.addValue("metric", m);
        }
        DateTimeFormatter local = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(TimeRange.LOCAL_ZONE);
        long[] count = {0};
        try {
            out.write('﻿');                           // BOM: Excel mở đúng UTF-8
            out.write("ts_utc,ts_local_vn,device_id,metric,value,unit,seq,received_at_utc,latency_ms\r\n");
            jdbc.query(sql, p, rs -> {
                try {
                    Instant ts = instant(rs, "ts");
                    Instant rec = instant(rs, "received_at");
                    long seq = rs.getLong("seq");
                    String seqStr = rs.wasNull() ? "" : String.valueOf(seq);
                    out.write(ts + "," + local.format(ts) + "," + code + "," + rs.getString("metric") + ","
                            + rs.getDouble("value") + "," + rs.getString("unit") + "," + seqStr + "," + rec + ","
                            + Duration.between(ts, rec).toMillis() + "\r\n");
                    count[0]++;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        audit.log(user.id(), user.username(), "DATA_EXPORT", "DEVICE", code, AuditResult.SUCCESS,
                Map.of("from", range.from().toString(), "to", range.to().toString(),
                        "metric", m == null ? "all" : m, "rows", count[0]));
    }

    /** Kiểm tra quyền + phạm vi thời gian trước khi controller mở luồng ghi file (để lỗi trả về JSON, không phải CSV hỏng). */
    public TimeRange checkExport(AuthUser user, String code, String metric, String from, String to) {
        deviceService.loadForView(user, code);
        checkMetric(metric);
        return TimeRange.parse(from, to, Duration.ofHours(24), RAW_MAX_SPAN);
    }

    // ====================================================================== Tiện ích
    private static MapSqlParameterSource params(Device d, TimeRange r) {
        return new MapSqlParameterSource("deviceId", d.getId())
                .addValue("from", Timestamp.from(r.from()))
                .addValue("to", Timestamp.from(r.to()));
    }

    private static String checkMetric(String metric) {
        if (metric == null || metric.isBlank() || "all".equalsIgnoreCase(metric)) {
            return null;
        }
        if (!METRICS.contains(metric)) {
            throw ApiException.badRequest("UNKNOWN_METRIC", "metric phải là gas, temp hoặc all");
        }
        return metric;
    }

    private static String intervalOf(Duration d) {
        return d.toSeconds() + " seconds";
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
