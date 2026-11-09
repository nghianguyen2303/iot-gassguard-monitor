package com.iot.gas.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.gas.web.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Trả 401 dạng JSON khi chưa đăng nhập hoặc token sai/hết hạn/bị thu hồi. */
@Component
@RequiredArgsConstructor
public class RestAuthEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Object reason = request.getAttribute(JwtAuthFilter.AUTH_ERROR_ATTR);
        String message = reason != null ? reason.toString() : "Chưa đăng nhập hoặc thiếu token";
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                ErrorResponse.of(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message, request.getRequestURI()));
    }
}
