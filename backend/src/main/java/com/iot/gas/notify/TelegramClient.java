package com.iot.gas.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gọi Telegram Bot API (https://core.telegram.org/bots/api) bằng HttpClient của JDK.
 * Bot token nằm trong URL nên tuyệt đối không ghi URL/token ra log hay thông báo lỗi.
 * api-base đổi được (vd. relay riêng) khi nhà mạng chặn api.telegram.org.
 */
@Component
@Slf4j
public class TelegramClient {

    private final String token;
    private final String apiBase;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile String botUsername;

    public TelegramClient(@Value("${app.telegram.bot-token:}") String token,
                          @Value("${app.telegram.api-base:https://api.telegram.org}") String apiBase,
                          ObjectMapper mapper) {
        this.token = token == null ? "" : token.trim();
        this.apiBase = apiBase.replaceAll("/+$", "");
        this.mapper = mapper;
    }

    public boolean enabled() {
        return !token.isEmpty();
    }

    /** Tên bot (không có @), lấy bằng getMe và nhớ lại. null nếu chưa cấu hình / không gọi được. */
    public String botUsername() {
        if (!enabled()) {
            return null;
        }
        if (botUsername == null) {
            try {
                botUsername = call("getMe", Map.of(), Duration.ofSeconds(8)).path("username").asText(null);
            } catch (TelegramException e) {
                log.warn("Telegram getMe thất bại: {}", e.getMessage());
            }
        }
        return botUsername;
    }

    public void sendMessage(long chatId, String html) throws TelegramException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", html);
        body.put("parse_mode", "HTML");
        body.put("disable_web_page_preview", true);
        call("sendMessage", body, Duration.ofSeconds(10));
    }

    /** Long polling: chờ tối đa timeoutSec giây để có tin nhắn mới gửi tới bot. */
    public JsonNode getUpdates(long offset, int timeoutSec) throws TelegramException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("offset", offset);
        body.put("timeout", timeoutSec);
        body.put("allowed_updates", new String[]{"message"});
        return call("getUpdates", body, Duration.ofSeconds(timeoutSec + 10L));
    }

    private JsonNode call(String method, Map<String, Object> body, Duration timeout) throws TelegramException {
        if (!enabled()) {
            throw new TelegramException("Chưa cấu hình TELEGRAM_BOT_TOKEN");
        }
        HttpResponse<String> resp;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + "/bot" + token + "/" + method))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (UnknownHostException e) {
            throw new TelegramException("Không phân giải được tên miền Telegram (DNS). Nhà mạng có thể đang chặn Telegram");
        } catch (ConnectException | HttpTimeoutException e) {
            throw new TelegramException("Không kết nối được tới Telegram (" + e.getClass().getSimpleName()
                    + "). Kiểm tra Internet hoặc nhà mạng đang chặn Telegram");
        } catch (IOException e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            String hint = root instanceof UnknownHostException
                    ? "Không phân giải được tên miền Telegram (DNS). Nhà mạng có thể đang chặn Telegram"
                    : "Lỗi mạng khi gọi Telegram (" + root.getClass().getSimpleName() + ")";
            throw new TelegramException(hint);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TelegramException("Bị ngắt khi gọi Telegram");
        }
        JsonNode json;
        try {
            json = mapper.readTree(resp.body());
        } catch (IOException e) {
            throw new TelegramException("Telegram trả về dữ liệu không hợp lệ (HTTP " + resp.statusCode() + ")");
        }
        if (!json.path("ok").asBoolean(false)) {
            int code = json.path("error_code").asInt(resp.statusCode());
            String desc = json.path("description").asText("không rõ");
            String hint = switch (code) {
                case 401, 404 -> " - bot token sai";
                case 403 -> " - người dùng đã chặn bot hoặc chưa bấm Start";
                case 409 -> " - bot đang dùng webhook hoặc có chương trình khác cùng đọc getUpdates";
                case 429 -> " - gửi quá nhanh, Telegram tạm giới hạn";
                default -> "";
            };
            throw new TelegramException("Telegram từ chối (" + code + "): " + desc + hint);
        }
        return json.path("result");
    }
}
