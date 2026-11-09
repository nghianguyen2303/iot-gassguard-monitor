package com.iot.gas.web;

import com.iot.gas.domain.enums.AuditResult;
import com.iot.gas.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private final AuditService audit;

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e, HttpServletRequest req) {
        return build(e.getStatus(), e.getCode(), e.getMessage(), req);
    }

    /** Dữ liệu đầu vào không hợp lệ (@Valid) -> 400 kèm lỗi từng trường. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e, HttpServletRequest req) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> fields.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        ErrorResponse body = new ErrorResponse(Instant.now(), 400, "VALIDATION_ERROR",
                "Dữ liệu không hợp lệ", req.getRequestURI(), fields);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "Nội dung JSON không hợp lệ", req);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> handleBadParam(Exception e, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "BAD_PARAMETER", e.getMessage(), req);
    }

    /** Bị @PreAuthorize từ chối -> 403 + ghi audit DENIED (bước 9 của demo). */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleDenied(AccessDeniedException e, HttpServletRequest req) {
        audit.logCurrent("ACCESS_DENIED", "API", req.getRequestURI(), AuditResult.DENIED,
                Map.of("method", req.getMethod()));
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "Bạn không có quyền thực hiện thao tác này", req);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrity(DataIntegrityViolationException e, HttpServletRequest req) {
        log.warn("Vi phạm ràng buộc dữ liệu: {}", e.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT, "CONSTRAINT_VIOLATION", "Dữ liệu vi phạm ràng buộc (trùng hoặc không hợp lệ)", req);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException e, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Không tìm thấy đường dẫn", req);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", e.getMessage(), req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception e, HttpServletRequest req) {
        log.error("Lỗi không mong đợi tại {}", req.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Lỗi hệ thống", req);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message, HttpServletRequest req) {
        return ResponseEntity.status(status).body(ErrorResponse.of(status, code, message, req.getRequestURI()));
    }
}
