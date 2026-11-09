package com.iot.gas.data;

import com.iot.gas.web.ApiException;

import java.time.*;
import java.time.format.DateTimeParseException;

/**
 * Khoảng thời gian [from, to) cho tra cứu. Nhận nhiều định dạng:
 * "2026-11-06T03:00:00Z", "2026-11-06T10:00:00+07:00", hoặc "2026-11-06T10:00" (hiểu là giờ Việt Nam).
 */
public record TimeRange(Instant from, Instant to) {

    public static final ZoneId LOCAL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    public static TimeRange parse(String from, String to, Duration defaultSpan, Duration maxSpan) {
        Instant end = (to == null || to.isBlank()) ? Instant.now() : parseInstant("to", to);
        Instant start = (from == null || from.isBlank()) ? end.minus(defaultSpan) : parseInstant("from", from);
        if (!start.isBefore(end)) {
            throw ApiException.badRequest("BAD_RANGE", "'from' phải trước 'to'");
        }
        if (Duration.between(start, end).compareTo(maxSpan) > 0) {
            throw ApiException.badRequest("RANGE_TOO_LARGE", "Khoảng thời gian tối đa " + maxSpan.toDays() + " ngày");
        }
        return new TimeRange(start, end);
    }

    public Duration span() {
        return Duration.between(from, to);
    }

    static Instant parseInstant(String name, String s) {
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(s).atZone(LOCAL_ZONE).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(s).atStartOfDay(LOCAL_ZONE).toInstant();
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("BAD_TIME", "'" + name + "' không đúng định dạng thời gian ISO-8601: " + s);
        }
    }
}
