package com.iot.gas.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Map;

/** Định dạng lỗi thống nhất cho mọi API. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(Instant timestamp, int status, String error, String message, String path,
                            Map<String, String> fieldErrors) {

    public static ErrorResponse of(HttpStatus status, String error, String message, String path) {
        return new ErrorResponse(Instant.now(), status.value(), error, message, path, null);
    }
}
