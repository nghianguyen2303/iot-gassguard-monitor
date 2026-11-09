package com.iot.gas.web;

import com.iot.gas.notify.TelegramLinkService;
import com.iot.gas.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Liên kết Telegram của chính người đang đăng nhập. */
@RestController
@RequestMapping("/api/auth/me/telegram")
@RequiredArgsConstructor
public class TelegramController {

    private final TelegramLinkService service;

    @GetMapping
    public Map<String, Object> status() {
        return service.status(CurrentUser.require());
    }

    @PostMapping("/link")
    public Map<String, Object> link() {
        return service.createLink(CurrentUser.require());
    }

    @PostMapping("/test")
    public Map<String, String> test() {
        service.sendTest(CurrentUser.require());
        return Map.of("message", "Đã gửi tin nhắn thử");
    }

    @DeleteMapping
    public Map<String, String> unlink() {
        service.unlink(CurrentUser.require());
        return Map.of("message", "Đã hủy liên kết Telegram");
    }
}
