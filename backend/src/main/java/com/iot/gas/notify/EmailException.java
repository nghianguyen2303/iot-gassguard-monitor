package com.iot.gas.notify;

/** Lỗi gửi email. Thông điệp KHÔNG chứa mật khẩu SMTP. */
public class EmailException extends Exception {
    public EmailException(String message) {
        super(message);
    }
}
