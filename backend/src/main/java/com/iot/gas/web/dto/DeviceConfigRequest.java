package com.iot.gas.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Chỉ gửi trường muốn đổi; trường bỏ trống giữ giá trị cũ. */
public record DeviceConfigRequest(
        @Min(value = 1, message = "Chu kỳ gửi tối thiểu 1 giây") @Max(value = 3600, message = "Chu kỳ gửi tối đa 3600 giây")
        Integer telemetryIntervalSec,
        @DecimalMin(value = "50", message = "Ngưỡng gas tối thiểu 50 ppm") @DecimalMax(value = "10000", message = "Ngưỡng gas tối đa 10000 ppm")
        Double gasThreshold,
        Boolean autoFan) {
}
