package com.iot.gas.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateDeviceRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 40) String deviceType,
        @Size(max = 120) String location,
        @Size(max = 60) String zone,
        @Size(max = 30) String hwVersion) {
}
