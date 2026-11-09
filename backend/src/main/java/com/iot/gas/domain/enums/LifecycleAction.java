package com.iot.gas.domain.enums;

/** Các thao tác chuyển trạng thái vòng đời mà người dùng được gọi qua API. */
public enum LifecycleAction { ACTIVATE, START_MAINTENANCE, END_MAINTENANCE, SUSPEND, RESUME, DECOMMISSION }
