-- =====================================================================================
-- GasGuard – SCHEMA CSDL ĐẦY ĐỦ (PostgreSQL 16), ẢNH CHỤP SAU MIGRATION V1–V5
--
-- File này SINH TỰ ĐỘNG bằng pg_dump --schema-only từ CSDL vừa chạy đủ 5 migration Flyway, để đọc và tham khảo
-- toàn bộ cấu trúc ở một chỗ (bảng, ràng buộc CHECK/UNIQUE/FK, chỉ mục). Có 9 bảng.
--
-- NGUỒN GỐC DUY NHẤT của schema là các migration trong
--   backend/src/main/resources/db/migration/V1__init_schema.sql ... V5__email_alerts.sql
-- Backend tự chạy Flyway khi khởi động; KHÔNG chạy file này vào CSDL của hệ thống (Flyway sẽ báo CSDL
-- đã có bảng nhưng không có lịch sử migration). Chỉ dùng để tạo CSDL độc lập phục vụ xem/thử truy vấn.
-- Khi thêm migration mới, sinh lại file này (xem database/README.md).
-- =====================================================================================

--
-- PostgreSQL database dump
--

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: alert_rules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.alert_rules (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    device_id bigint,
    metric character varying(20) NOT NULL,
    operator character varying(3) NOT NULL,
    threshold double precision NOT NULL,
    severity character varying(10) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT alert_rules_operator_check CHECK (((operator)::text = ANY ((ARRAY['GT'::character varying, 'GTE'::character varying, 'LT'::character varying, 'LTE'::character varying])::text[]))),
    CONSTRAINT alert_rules_severity_check CHECK (((severity)::text = ANY ((ARRAY['INFO'::character varying, 'WARNING'::character varying, 'CRITICAL'::character varying])::text[])))
);

--
-- Name: alert_rules_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.alert_rules_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: alert_rules_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.alert_rules_id_seq OWNED BY public.alert_rules.id;

--
-- Name: alerts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.alerts (
    id bigint NOT NULL,
    device_id bigint NOT NULL,
    rule_id bigint,
    type character varying(12) NOT NULL,
    severity character varying(10) NOT NULL,
    message character varying(255) NOT NULL,
    metric character varying(20),
    value double precision,
    status character varying(12) DEFAULT 'OPEN'::character varying NOT NULL,
    occurrence_count integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_seen_at timestamp with time zone DEFAULT now() NOT NULL,
    cleared_at timestamp with time zone,
    acknowledged_by bigint,
    acknowledged_at timestamp with time zone,
    resolved_by bigint,
    resolved_at timestamp with time zone,
    resolution_note character varying(500),
    CONSTRAINT alerts_severity_check CHECK (((severity)::text = ANY ((ARRAY['INFO'::character varying, 'WARNING'::character varying, 'CRITICAL'::character varying])::text[]))),
    CONSTRAINT alerts_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'ACKNOWLEDGED'::character varying, 'RESOLVED'::character varying])::text[]))),
    CONSTRAINT alerts_type_check CHECK (((type)::text = ANY ((ARRAY['THRESHOLD'::character varying, 'OFFLINE'::character varying, 'FAULT'::character varying])::text[])))
);

--
-- Name: alerts_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.alerts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: alerts_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.alerts_id_seq OWNED BY public.alerts.id;

--
-- Name: audit_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_logs (
    id bigint NOT NULL,
    actor_id bigint,
    actor_username character varying(50),
    action character varying(50) NOT NULL,
    target_type character varying(30),
    target_id character varying(60),
    result character varying(10) NOT NULL,
    details jsonb,
    ip_address character varying(45),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT audit_logs_result_check CHECK (((result)::text = ANY ((ARRAY['SUCCESS'::character varying, 'DENIED'::character varying, 'FAILED'::character varying])::text[])))
);

--
-- Name: audit_logs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.audit_logs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: audit_logs_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.audit_logs_id_seq OWNED BY public.audit_logs.id;

--
-- Name: commands; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.commands (
    id uuid NOT NULL,
    device_id bigint NOT NULL,
    action character varying(30) NOT NULL,
    params jsonb DEFAULT '{}'::jsonb NOT NULL,
    status character varying(10) DEFAULT 'PENDING'::character varying NOT NULL,
    requested_by bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    sent_at timestamp with time zone,
    acked_at timestamp with time zone,
    result_message character varying(255),
    result_state jsonb,
    CONSTRAINT commands_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SENT'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'TIMEOUT'::character varying])::text[])))
);

--
-- Name: device_credentials; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.device_credentials (
    id bigint NOT NULL,
    device_id bigint NOT NULL,
    mqtt_username character varying(60) NOT NULL,
    client_id character varying(60) NOT NULL,
    secret_fingerprint character varying(64) NOT NULL,
    status character varying(10) DEFAULT 'ACTIVE'::character varying NOT NULL,
    issued_at timestamp with time zone DEFAULT now() NOT NULL,
    issued_by bigint,
    revoked_at timestamp with time zone,
    revoked_reason character varying(255),
    CONSTRAINT device_credentials_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DISABLED'::character varying, 'REVOKED'::character varying])::text[])))
);

--
-- Name: device_credentials_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.device_credentials_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: device_credentials_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.device_credentials_id_seq OWNED BY public.device_credentials.id;

--
-- Name: device_shares; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.device_shares (
    device_id bigint NOT NULL,
    user_id bigint NOT NULL,
    permission character varying(10) DEFAULT 'VIEW'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT device_shares_permission_check CHECK (((permission)::text = 'VIEW'::text))
);

--
-- Name: devices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.devices (
    id bigint NOT NULL,
    device_code character varying(40) NOT NULL,
    name character varying(100) NOT NULL,
    device_type character varying(40) DEFAULT 'GAS_NODE'::character varying NOT NULL,
    location character varying(120),
    zone character varying(60),
    owner_id bigint NOT NULL,
    lifecycle_status character varying(20) DEFAULT 'REGISTERED'::character varying NOT NULL,
    connectivity character varying(10) DEFAULT 'OFFLINE'::character varying NOT NULL,
    last_seen_at timestamp with time zone,
    hw_version character varying(30),
    fw_version character varying(30),
    config jsonb DEFAULT '{}'::jsonb NOT NULL,
    config_version integer DEFAULT 0 NOT NULL,
    config_applied_version integer DEFAULT 0 NOT NULL,
    actuator_state jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    decommissioned_at timestamp with time zone,
    config_status character varying(10) DEFAULT 'NONE'::character varying NOT NULL,
    config_message character varying(255),
    config_acked_at timestamp with time zone,
    CONSTRAINT devices_config_status_check CHECK (((config_status)::text = ANY ((ARRAY['NONE'::character varying, 'PENDING'::character varying, 'APPLIED'::character varying, 'REJECTED'::character varying])::text[]))),
    CONSTRAINT devices_connectivity_check CHECK (((connectivity)::text = ANY ((ARRAY['ONLINE'::character varying, 'OFFLINE'::character varying, 'FAULT'::character varying])::text[]))),
    CONSTRAINT devices_device_code_check CHECK (((device_code)::text ~ '^[a-z0-9][a-z0-9-]{2,39}$'::text)),
    CONSTRAINT devices_lifecycle_status_check CHECK (((lifecycle_status)::text = ANY ((ARRAY['REGISTERED'::character varying, 'PROVISIONED'::character varying, 'ACTIVE'::character varying, 'MAINTENANCE'::character varying, 'SUSPENDED'::character varying, 'DECOMMISSIONED'::character varying])::text[])))
);

--
-- Name: devices_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.devices_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: devices_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.devices_id_seq OWNED BY public.devices.id;

--
-- Name: telemetry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.telemetry (
    id bigint NOT NULL,
    device_id bigint NOT NULL,
    metric character varying(20) NOT NULL,
    value double precision NOT NULL,
    unit character varying(10) NOT NULL,
    seq bigint,
    ts timestamp with time zone NOT NULL,
    received_at timestamp with time zone DEFAULT now() NOT NULL
);

--
-- Name: telemetry_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.telemetry_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: telemetry_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.telemetry_id_seq OWNED BY public.telemetry.id;

--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id bigint NOT NULL,
    username character varying(50) NOT NULL,
    email character varying(120),
    full_name character varying(100),
    password_hash character varying(100) NOT NULL,
    role character varying(20) NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    failed_login_count integer DEFAULT 0 NOT NULL,
    locked_until timestamp with time zone,
    token_version integer DEFAULT 0 NOT NULL,
    last_login_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    telegram_chat_id bigint,
    telegram_linked_at timestamp with time zone,
    alert_email character varying(120),
    alert_email_verified_at timestamp with time zone,
    CONSTRAINT users_role_check CHECK (((role)::text = ANY ((ARRAY['ADMIN'::character varying, 'OWNER'::character varying, 'VIEWER'::character varying])::text[]))),
    CONSTRAINT users_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'LOCKED'::character varying])::text[])))
);

--
-- Name: users_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.users_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: users_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.users_id_seq OWNED BY public.users.id;

--
-- Name: alert_rules id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_rules ALTER COLUMN id SET DEFAULT nextval('public.alert_rules_id_seq'::regclass);

--
-- Name: alerts id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alerts ALTER COLUMN id SET DEFAULT nextval('public.alerts_id_seq'::regclass);

--
-- Name: audit_logs id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs ALTER COLUMN id SET DEFAULT nextval('public.audit_logs_id_seq'::regclass);

--
-- Name: device_credentials id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.device_credentials ALTER COLUMN id SET DEFAULT nextval('public.device_credentials_id_seq'::regclass);

--
-- Name: devices id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.devices ALTER COLUMN id SET DEFAULT nextval('public.devices_id_seq'::regclass);

--
-- Name: telemetry id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.telemetry ALTER COLUMN id SET DEFAULT nextval('public.telemetry_id_seq'::regclass);

--
-- Name: users id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users ALTER COLUMN id SET DEFAULT nextval('public.users_id_seq'::regclass);

--
-- Name: alert_rules alert_rules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_rules
    ADD CONSTRAINT alert_rules_pkey PRIMARY KEY (id);

--
-- Name: alerts alerts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alerts
    ADD CONSTRAINT alerts_pkey PRIMARY KEY (id);

--
-- Name: audit_logs audit_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);

--
-- Name: commands commands_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.commands
    ADD CONSTRAINT commands_pkey PRIMARY KEY (id);

--
-- Name: device_credentials device_credentials_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.device_credentials
    ADD CONSTRAINT device_credentials_pkey PRIMARY KEY (id);

--
-- Name: device_shares device_shares_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.device_shares
    ADD CONSTRAINT device_shares_pkey PRIMARY KEY (device_id, user_id);

--
-- Name: devices devices_device_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.devices
    ADD CONSTRAINT devices_device_code_key UNIQUE (device_code);

--
-- Name: devices devices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.devices
    ADD CONSTRAINT devices_pkey PRIMARY KEY (id);

--
-- Name: telemetry telemetry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.telemetry
    ADD CONSTRAINT telemetry_pkey PRIMARY KEY (id);

--
-- Name: telemetry uq_telemetry_dedup; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.telemetry
    ADD CONSTRAINT uq_telemetry_dedup UNIQUE (device_id, metric, ts);

--
-- Name: users users_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);

--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

--
-- Name: users users_username_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_username_key UNIQUE (username);

--
-- Name: idx_alerts_device_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_alerts_device_time ON public.alerts USING btree (device_id, created_at DESC);

--
-- Name: idx_alerts_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_alerts_status ON public.alerts USING btree (status);

--
-- Name: idx_audit_actor; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_actor ON public.audit_logs USING btree (actor_id);

--
-- Name: idx_audit_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_time ON public.audit_logs USING btree (created_at DESC);

--
-- Name: idx_commands_device_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_commands_device_time ON public.commands USING btree (device_id, created_at DESC);

--
-- Name: idx_commands_waiting; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_commands_waiting ON public.commands USING btree (sent_at) WHERE ((status)::text = 'SENT'::text);

--
-- Name: idx_devices_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_devices_owner ON public.devices USING btree (owner_id);

--
-- Name: idx_telemetry_query; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_telemetry_query ON public.telemetry USING btree (device_id, metric, ts DESC);

--
-- Name: idx_users_telegram; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_telegram ON public.users USING btree (telegram_chat_id) WHERE (telegram_chat_id IS NOT NULL);

--
-- Name: uq_alert_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_alert_active ON public.alerts USING btree (device_id, type, COALESCE(rule_id, (0)::bigint)) WHERE ((status)::text <> 'RESOLVED'::text);

--
-- Name: uq_credential_live; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_credential_live ON public.device_credentials USING btree (device_id) WHERE ((status)::text <> 'REVOKED'::text);

--
-- Name: alert_rules alert_rules_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_rules
    ADD CONSTRAINT alert_rules_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);

--
-- Name: alert_rules alert_rules_device_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_rules
    ADD CONSTRAINT alert_rules_device_id_fkey FOREIGN KEY (device_id) REFERENCES public.devices(id) ON DELETE CASCADE;

--
-- Name: alerts alerts_acknowledged_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alerts
    ADD CONSTRAINT alerts_acknowledged_by_fkey FOREIGN KEY (acknowledged_by) REFERENCES public.users(id);

--
-- Name: alerts alerts_device_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alerts
    ADD CONSTRAINT alerts_device_id_fkey FOREIGN KEY (device_id) REFERENCES public.devices(id);

--
-- Name: alerts alerts_resolved_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alerts
    ADD CONSTRAINT alerts_resolved_by_fkey FOREIGN KEY (resolved_by) REFERENCES public.users(id);

--
-- Name: alerts alerts_rule_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alerts
    ADD CONSTRAINT alerts_rule_id_fkey FOREIGN KEY (rule_id) REFERENCES public.alert_rules(id) ON DELETE SET NULL;

--
-- Name: audit_logs audit_logs_actor_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES public.users(id);

--
-- Name: commands commands_device_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.commands
    ADD CONSTRAINT commands_device_id_fkey FOREIGN KEY (device_id) REFERENCES public.devices(id);

--
-- Name: commands commands_requested_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.commands
    ADD CONSTRAINT commands_requested_by_fkey FOREIGN KEY (requested_by) REFERENCES public.users(id);

--
-- Name: device_credentials device_credentials_device_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.device_credentials
    ADD CONSTRAINT device_credentials_device_id_fkey FOREIGN KEY (device_id) REFERENCES public.devices(id);

--
-- Name: device_credentials device_credentials_issued_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.device_credentials
    ADD CONSTRAINT device_credentials_issued_by_fkey FOREIGN KEY (issued_by) REFERENCES public.users(id);

--
-- Name: device_shares device_shares_device_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.device_shares
    ADD CONSTRAINT device_shares_device_id_fkey FOREIGN KEY (device_id) REFERENCES public.devices(id) ON DELETE CASCADE;

--
-- Name: device_shares device_shares_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.device_shares
    ADD CONSTRAINT device_shares_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

--
-- Name: devices devices_owner_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.devices
    ADD CONSTRAINT devices_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.users(id);

--
-- Name: telemetry telemetry_device_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.telemetry
    ADD CONSTRAINT telemetry_device_id_fkey FOREIGN KEY (device_id) REFERENCES public.devices(id);

--
-- PostgreSQL database dump complete
--

