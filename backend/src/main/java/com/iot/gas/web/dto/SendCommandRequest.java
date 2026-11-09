package com.iot.gas.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Ví dụ: {"action":"FAN_SET","params":{"on":true}}  hoặc  {"action":"PING"} */
public record SendCommandRequest(@NotBlank @Size(max = 30) String action, Map<String, Object> params) {
}
