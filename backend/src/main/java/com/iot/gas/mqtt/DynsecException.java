package com.iot.gas.mqtt;

import com.iot.gas.web.ApiException;
import org.springframework.http.HttpStatus;

/** Lỗi khi điều khiển Dynamic Security của broker -> API trả 502 và transaction bị rollback. */
public class DynsecException extends ApiException {
    public DynsecException(String message) {
        super(HttpStatus.BAD_GATEWAY, "BROKER_ERROR", message);
    }
}
