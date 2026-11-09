-- =========================================================
-- V1: Schema khởi tạo cho hệ thống giám sát rò rỉ khí gas
-- Mọi thời gian lưu theo UTC (TIMESTAMPTZ)
-- =========================================================

-- ---------- Người dùng ----------
CREATE TABLE users (
    id                  BIGSERIAL    PRIMARY KEY,
    username            VARCHAR(50)  NOT NULL UNIQUE,
    email               VARCHAR(120) UNIQUE,
    full_name           VARCHAR(100),
    password_hash       VARCHAR(100) NOT NULL,
    role                VARCHAR(20)  NOT NULL CHECK (role IN ('ADMIN', 'OWNER', 'VIEWER')),
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LOCKED')),
    failed_login_count  INT          NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    token_version       INT          NOT NULL DEFAULT 0,
    last_login_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------- Thiết bị ----------
CREATE TABLE devices (
    id                      BIGSERIAL    PRIMARY KEY,
    device_code             VARCHAR(40)  NOT NULL UNIQUE
                            CHECK (device_code ~ '^[a-z0-9][a-z0-9-]{2,39}$'),
    name                    VARCHAR(100) NOT NULL,
    device_type             VARCHAR(40)  NOT NULL DEFAULT 'GAS_NODE',
    location                VARCHAR(120),
    zone                    VARCHAR(60),
    owner_id                BIGINT       NOT NULL REFERENCES users(id),
    lifecycle_status        VARCHAR(20)  NOT NULL DEFAULT 'REGISTERED'
                            CHECK (lifecycle_status IN ('REGISTERED', 'PROVISIONED', 'ACTIVE',
                                                        'MAINTENANCE', 'SUSPENDED', 'DECOMMISSIONED')),
    connectivity            VARCHAR(10)  NOT NULL DEFAULT 'OFFLINE'
                            CHECK (connectivity IN ('ONLINE', 'OFFLINE', 'FAULT')),
    last_seen_at            TIMESTAMPTZ,
    hw_version              VARCHAR(30),
    fw_version              VARCHAR(30),
    config                  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    config_version          INT          NOT NULL DEFAULT 0,
    config_applied_version  INT          NOT NULL DEFAULT 0,
    actuator_state          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decommissioned_at       TIMESTAMPTZ
);
CREATE INDEX idx_devices_owner ON devices(owner_id);

-- ---------- Chia sẻ quyền xem thiết bị ----------
CREATE TABLE device_shares (
    device_id   BIGINT      NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    user_id     BIGINT      NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    permission  VARCHAR(10) NOT NULL DEFAULT 'VIEW' CHECK (permission IN ('VIEW')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, user_id)
);

-- ---------- Thông tin xác thực MQTT của thiết bị ----------
CREATE TABLE device_credentials (
    id                  BIGSERIAL   PRIMARY KEY,
    device_id           BIGINT      NOT NULL REFERENCES devices(id),
    mqtt_username       VARCHAR(60) NOT NULL,
    client_id           VARCHAR(60) NOT NULL,
    secret_fingerprint  VARCHAR(64) NOT NULL,           -- SHA-256 hex, KHÔNG lưu mật khẩu
    status              VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'DISABLED', 'REVOKED')),
    issued_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    issued_by           BIGINT      REFERENCES users(id),
    revoked_at          TIMESTAMPTZ,
    revoked_reason      VARCHAR(255)
);
-- Mỗi thiết bị chỉ có tối đa 1 credential chưa bị thu hồi
CREATE UNIQUE INDEX uq_credential_live ON device_credentials(device_id) WHERE status <> 'REVOKED';

-- ---------- Telemetry ----------
CREATE TABLE telemetry (
    id           BIGSERIAL        PRIMARY KEY,
    device_id    BIGINT           NOT NULL REFERENCES devices(id),
    metric       VARCHAR(20)      NOT NULL,              -- 'gas', 'temp'
    value        DOUBLE PRECISION NOT NULL,
    unit         VARCHAR(10)      NOT NULL,              -- 'ppm', 'C'
    seq          BIGINT,
    ts           TIMESTAMPTZ      NOT NULL,              -- thời điểm đo (thiết bị, NTP)
    received_at  TIMESTAMPTZ      NOT NULL DEFAULT now(),-- thời điểm backend nhận
    CONSTRAINT uq_telemetry_dedup UNIQUE (device_id, metric, ts)
);
CREATE INDEX idx_telemetry_query ON telemetry(device_id, metric, ts DESC);

-- ---------- Luật cảnh báo ----------
CREATE TABLE alert_rules (
    id          BIGSERIAL        PRIMARY KEY,
    name        VARCHAR(100)     NOT NULL,
    device_id   BIGINT           REFERENCES devices(id) ON DELETE CASCADE,  -- NULL = áp dụng mọi thiết bị
    metric      VARCHAR(20)      NOT NULL,
    operator    VARCHAR(3)       NOT NULL CHECK (operator IN ('GT', 'GTE', 'LT', 'LTE')),
    threshold   DOUBLE PRECISION NOT NULL,
    severity    VARCHAR(10)      NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    enabled     BOOLEAN          NOT NULL DEFAULT TRUE,
    created_by  BIGINT           REFERENCES users(id),
    created_at  TIMESTAMPTZ      NOT NULL DEFAULT now()
);

-- ---------- Cảnh báo ----------
CREATE TABLE alerts (
    id                BIGSERIAL        PRIMARY KEY,
    device_id         BIGINT           NOT NULL REFERENCES devices(id),
    rule_id           BIGINT           REFERENCES alert_rules(id) ON DELETE SET NULL,
    type              VARCHAR(12)      NOT NULL CHECK (type IN ('THRESHOLD', 'OFFLINE', 'FAULT')),
    severity          VARCHAR(10)      NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    message           VARCHAR(255)     NOT NULL,
    metric            VARCHAR(20),
    value             DOUBLE PRECISION,
    status            VARCHAR(12)      NOT NULL DEFAULT 'OPEN'
                      CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    occurrence_count  INT              NOT NULL DEFAULT 1,
    created_at        TIMESTAMPTZ      NOT NULL DEFAULT now(),
    last_seen_at      TIMESTAMPTZ      NOT NULL DEFAULT now(),
    cleared_at        TIMESTAMPTZ,                       -- điều kiện đã trở lại bình thường
    acknowledged_by   BIGINT           REFERENCES users(id),
    acknowledged_at   TIMESTAMPTZ,
    resolved_by       BIGINT           REFERENCES users(id),
    resolved_at       TIMESTAMPTZ,
    resolution_note   VARCHAR(500)
);
-- Mỗi (thiết bị, loại, luật) chỉ có 1 cảnh báo chưa xử lý -> không spam cảnh báo
CREATE UNIQUE INDEX uq_alert_active
    ON alerts(device_id, type, COALESCE(rule_id, 0)) WHERE status <> 'RESOLVED';
CREATE INDEX idx_alerts_device_time ON alerts(device_id, created_at DESC);
CREATE INDEX idx_alerts_status ON alerts(status);

-- ---------- Lệnh điều khiển ----------
CREATE TABLE commands (
    id              UUID         PRIMARY KEY,           -- chính là commandId gửi xuống thiết bị
    device_id       BIGINT       NOT NULL REFERENCES devices(id),
    action          VARCHAR(30)  NOT NULL,
    params          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status          VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'SENT', 'SUCCESS', 'FAILED', 'TIMEOUT')),
    requested_by    BIGINT       NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    acked_at        TIMESTAMPTZ,
    result_message  VARCHAR(255),
    result_state    JSONB
);
CREATE INDEX idx_commands_device_time ON commands(device_id, created_at DESC);
CREATE INDEX idx_commands_waiting ON commands(sent_at) WHERE status = 'SENT';

-- ---------- Audit log ----------
CREATE TABLE audit_logs (
    id              BIGSERIAL    PRIMARY KEY,
    actor_id        BIGINT       REFERENCES users(id),
    actor_username  VARCHAR(50),
    action          VARCHAR(50)  NOT NULL,
    target_type     VARCHAR(30),
    target_id       VARCHAR(60),
    result          VARCHAR(10)  NOT NULL CHECK (result IN ('SUCCESS', 'DENIED', 'FAILED')),
    details         JSONB,
    ip_address      VARCHAR(45),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_time ON audit_logs(created_at DESC);
CREATE INDEX idx_audit_actor ON audit_logs(actor_id);