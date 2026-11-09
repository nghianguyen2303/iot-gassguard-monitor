package com.iot.gas.ingest;

/** Một chỉ số đo đã hợp lệ, ví dụ gas = 412.0 ppm. */
public record Reading(String metric, double value, String unit) {
}
