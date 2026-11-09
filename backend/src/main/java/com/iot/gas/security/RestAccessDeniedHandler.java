package com.iot.gas.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.gas.domain.enums.AuditResult;
import com.iot.gas.service.AuditService;
import com.iot.gas.web.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/** Trả 403 dạng JSON khi đã đăng nhập nhưng không đủ quyền (theo URL), và ghi audit DENIED. */
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final AuditService audit;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException ex) throws IOException {
        audit.logCurrent("ACCESS_DENIED", "API", request.getRequestURI(), AuditResult.DENIED,
                Map.of("method", request.getMethod()));
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                ErrorResponse.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Bạn không có quyền thực hiện thao tác này",
                        request.getRequestURI()));
    }
}
