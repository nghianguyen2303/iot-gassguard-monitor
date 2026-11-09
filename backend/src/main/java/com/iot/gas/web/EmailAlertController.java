package com.iot.gas.web;

import com.iot.gas.notify.EmailAlertService;
import com.iot.gas.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Bật / tắt nhận cảnh báo qua email của chính người đang đăng nhập (xác nhận bằng mã 6 số). */
@RestController
@RequestMapping("/api/auth/me/email-alerts")
@RequiredArgsConstructor
public class EmailAlertController {

    private final EmailAlertService service;

    @GetMapping
    public Map<String, Object> status() {
        return service.status(CurrentUser.require());
    }

    /** body: {"email": "..."} -> gửi mã 6 số tới email đó */
    @PostMapping("/request")
    public Map<String, Object> request(@RequestBody Map<String, String> body) {
        return service.requestCode(CurrentUser.require(), body.get("email"));
    }

    /** body: {"code": "123456"} */
    @PostMapping("/confirm")
    public Map<String, Object> confirm(@RequestBody Map<String, String> body) {
        return service.confirm(CurrentUser.require(), body.get("code"));
    }

    @PostMapping("/test")
    public Map<String, String> test() {
        service.sendTest(CurrentUser.require());
        return Map.of("message", "Đã gửi email thử");
    }

    @DeleteMapping
    public Map<String, String> disable() {
        service.disable(CurrentUser.require());
        return Map.of("message", "Đã tắt nhận cảnh báo qua email");
    }
}
