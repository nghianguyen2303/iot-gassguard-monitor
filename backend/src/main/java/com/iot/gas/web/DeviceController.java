package com.iot.gas.web;

import com.iot.gas.security.CurrentUser;
import com.iot.gas.service.DeviceService;
import com.iot.gas.web.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API quản lý thiết bị. Quyền trên từng thiết bị được kiểm tra trong DeviceService/AccessService
 * (không chỉ ẩn nút trên giao diện - mục 4.5).
 */
@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @GetMapping
    public List<DeviceDto> list(@RequestParam(defaultValue = "false") boolean includeDecommissioned) {
        return deviceService.list(CurrentUser.require(), includeDecommissioned);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public DeviceDto register(@Valid @RequestBody RegisterDeviceRequest req) {
        return deviceService.register(CurrentUser.require(), req);
    }

    @GetMapping("/{code}")
    public DeviceDto get(@PathVariable String code) {
        return deviceService.get(CurrentUser.require(), code);
    }

    @PutMapping("/{code}")
    public DeviceDto update(@PathVariable String code, @Valid @RequestBody UpdateDeviceRequest req) {
        return deviceService.update(CurrentUser.require(), code, req);
    }

    /** Cấp thông tin xác thực MQTT lần đầu. Mật khẩu chỉ trả về ở đây, 1 lần. */
    @PostMapping("/{code}/provision")
    @ResponseStatus(HttpStatus.CREATED)
    public ProvisionResponse provision(@PathVariable String code) {
        return deviceService.provision(CurrentUser.require(), code);
    }

    @PostMapping("/{code}/credentials/rotate")
    public ProvisionResponse rotate(@PathVariable String code) {
        return deviceService.rotateCredentials(CurrentUser.require(), code);
    }

    @GetMapping("/{code}/credentials")
    public List<CredentialDto> credentials(@PathVariable String code) {
        return deviceService.credentialHistory(CurrentUser.require(), code);
    }

    /** ACTIVATE | START_MAINTENANCE | END_MAINTENANCE | SUSPEND | RESUME | DECOMMISSION */
    @PostMapping("/{code}/lifecycle")
    public DeviceDto lifecycle(@PathVariable String code, @Valid @RequestBody LifecycleRequest req) {
        return deviceService.changeLifecycle(CurrentUser.require(), code, req);
    }

    @GetMapping("/{code}/shares")
    public List<ShareDto> shares(@PathVariable String code) {
        return deviceService.listShares(CurrentUser.require(), code);
    }

    @PostMapping("/{code}/shares")
    @ResponseStatus(HttpStatus.CREATED)
    public ShareDto addShare(@PathVariable String code, @Valid @RequestBody ShareRequest req) {
        return deviceService.addShare(CurrentUser.require(), code, req.username());
    }

    @DeleteMapping("/{code}/shares/{username}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeShare(@PathVariable String code, @PathVariable String username) {
        deviceService.removeShare(CurrentUser.require(), code, username);
    }
}
