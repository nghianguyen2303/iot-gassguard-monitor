package com.iot.gas.web.dto;

/** Quy tắc mật khẩu: 8-72 ký tự (72 là giới hạn của BCrypt), có ít nhất 1 chữ cái và 1 chữ số. */
public final class PasswordPolicy {
    private PasswordPolicy() {
    }

    public static final String REGEX = "^(?=.*[A-Za-z])(?=.*\\d).{8,72}$";
    public static final String MESSAGE = "Mật khẩu 8-72 ký tự, có ít nhất 1 chữ cái và 1 chữ số";

    public static final String USERNAME_REGEX = "^[a-zA-Z0-9_.]{3,50}$";
    public static final String USERNAME_MESSAGE = "Tên đăng nhập 3-50 ký tự, chỉ gồm chữ, số, '_' hoặc '.'";
}
