package com.iot.gas.web;

import com.iot.gas.security.CurrentUser;
import com.iot.gas.service.ConfigService;
import com.iot.gas.web.dto.DeviceConfigDto;
import com.iot.gas.web.dto.DeviceConfigRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/devices/{code}/config")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigService configService;

    @GetMapping
    public DeviceConfigDto get(@PathVariable String code) {
        return configService.get(CurrentUser.require(), code);
    }

    @PutMapping
    public DeviceConfigDto update(@PathVariable String code, @Valid @RequestBody DeviceConfigRequest req) {
        return configService.update(CurrentUser.require(), code, req);
    }
}
