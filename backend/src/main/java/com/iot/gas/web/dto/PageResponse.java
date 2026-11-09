package com.iot.gas.web.dto;

import java.util.List;

public record PageResponse<T>(List<T> items, long total, int page, int size) {
}
