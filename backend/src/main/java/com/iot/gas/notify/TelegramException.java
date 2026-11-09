package com.iot.gas.notify;

/** Lỗi gọi Telegram Bot API. Thông điệp KHÔNG chứa bot token. */
public class TelegramException extends Exception {
    public TelegramException(String message) {
        super(message);
    }
}
