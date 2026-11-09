package com.iot.gas.web.dto;

import jakarta.validation.constraints.Size;

/** note: ghi chú khi xác nhận (tùy chọn) hoặc khi xử lý xong (bắt buộc, mô tả cách đã xử lý). */
public record AlertActionRequest(@Size(max = 500) String note) {
}
