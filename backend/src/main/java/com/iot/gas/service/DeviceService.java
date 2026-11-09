package com.iot.gas.service;

import com.iot.gas.domain.Device;
import com.iot.gas.domain.DeviceCredential;
import com.iot.gas.domain.DeviceShare;
import com.iot.gas.domain.User;
import com.iot.gas.domain.enums.*;
import com.iot.gas.ingest.event.ConnectivityChangedEvent;
import com.iot.gas.mqtt.DynsecClient;
import com.iot.gas.mqtt.MqttGateway;
import com.iot.gas.mqtt.MqttTopics;
import com.iot.gas.repository.DeviceCredentialRepository;
import com.iot.gas.repository.DeviceRepository;
import com.iot.gas.repository.DeviceShareRepository;
import com.iot.gas.repository.UserRepository;
import com.iot.gas.security.AuthUser;
import com.iot.gas.service.event.DeviceLifecycleChangedEvent;
import com.iot.gas.web.ApiException;
import com.iot.gas.web.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Quản lý vòng đời thiết bị (mục 4.4b):
 * 1 đăng ký, 2 cấp định danh + thông tin xác thực, 3 gán chủ/vị trí/khu vực, 4 kích hoạt,
 * 6 theo dõi online/offline (Bước 6), 7 phiên bản phần cứng/firmware, 8 bảo trì,
 * 9 tạm khóa/thu hồi quyền kết nối, 10 ngừng sử dụng. (5 cấu hình từ xa: Bước 8)
 * <p>
 * Mọi thao tác ghi DB trước rồi mới gọi broker trong cùng transaction: broker lỗi -> ném 502 -> DB rollback,
 * nên trạng thái trong DB và quyền trên broker không bị lệch nhau.
 */
@Service
@Slf4j
public class DeviceService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeviceRepository devices;
    private final DeviceCredentialRepository credentials;
    private final DeviceShareRepository shares;
    private final UserRepository users;
    private final AccessService access;
    private final DynsecClient dynsec;
    private final MqttGateway gateway;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final String deviceMqttUrl;

    public DeviceService(DeviceRepository devices, DeviceCredentialRepository credentials, DeviceShareRepository shares,
                         UserRepository users, AccessService access, DynsecClient dynsec, MqttGateway gateway,
                         AuditService audit, ApplicationEventPublisher events,
                         @Value("${app.mqtt.device-url:tcp://<IP-may-chay-broker>:1883}") String deviceMqttUrl) {
        this.devices = devices;
        this.credentials = credentials;
        this.shares = shares;
        this.users = users;
        this.access = access;
        this.dynsec = dynsec;
        this.gateway = gateway;
        this.audit = audit;
        this.events = events;
        this.deviceMqttUrl = deviceMqttUrl;
    }

    // ====================================================================== Đọc
    @Transactional(readOnly = true)
    public List<DeviceDto> list(AuthUser user, boolean includeDecommissioned) {
        List<Device> list = user.isAdmin() ? devices.findAll(Sort.by("id")) : devices.findAccessibleBy(user.id());
        if (!includeDecommissioned) {
            list = list.stream().filter(d -> d.getLifecycleStatus() != LifecycleStatus.DECOMMISSIONED).toList();
        }
        Map<Long, String> owners = users.findAllById(list.stream().map(Device::getOwnerId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(User::getId, User::getUsername));
        return list.stream().map(d -> toDto(user, d, owners.get(d.getOwnerId()))).toList();
    }

    @Transactional(readOnly = true)
    public DeviceDto get(AuthUser user, String code) {
        Device d = load(code);
        access.requireView(user, d);
        return toDto(user, d);
    }

    /** Dùng chung cho các service khác (telemetry, lệnh, cảnh báo...): tải thiết bị + kiểm tra quyền xem. */
    @Transactional(readOnly = true)
    public Device loadForView(AuthUser user, String code) {
        Device d = load(code);
        access.requireView(user, d);
        return d;
    }

    @Transactional(readOnly = true)
    public Device loadForManage(AuthUser user, String code) {
        Device d = load(code);
        access.requireManage(user, d);
        return d;
    }

    // ====================================================================== 1. Đăng ký + 3. Gán chủ/vị trí
    @Transactional
    public DeviceDto register(AuthUser user, RegisterDeviceRequest req) {
        if (devices.existsByDeviceCode(req.deviceCode())) {
            throw ApiException.conflict("DEVICE_EXISTS", "Mã thiết bị đã tồn tại (kể cả thiết bị đã ngừng sử dụng)");
        }
        Long ownerId = user.id();
        if (req.ownerUsername() != null && !req.ownerUsername().isBlank()
                && !req.ownerUsername().equals(user.username())) {
            if (!user.isAdmin()) {
                throw ApiException.forbidden("Chỉ quản trị viên được gán thiết bị cho người khác");
            }
            User owner = users.findByUsername(req.ownerUsername().trim())
                    .orElseThrow(() -> ApiException.badRequest("OWNER_NOT_FOUND", "Không tìm thấy người dùng chủ sở hữu"));
            if (owner.getRole() == Role.VIEWER) {
                throw ApiException.badRequest("OWNER_ROLE", "Chủ sở hữu phải có vai trò OWNER hoặc ADMIN");
            }
            ownerId = owner.getId();
        }
        Device d = new Device();
        d.setDeviceCode(req.deviceCode());
        d.setName(req.name().trim());
        if (req.deviceType() != null && !req.deviceType().isBlank()) {
            d.setDeviceType(req.deviceType().trim());
        }
        d.setLocation(blankToNull(req.location()));
        d.setZone(blankToNull(req.zone()));
        d.setHwVersion(blankToNull(req.hwVersion()));
        d.setOwnerId(ownerId);
        devices.save(d);
        audit.log(user.id(), user.username(), "DEVICE_REGISTER", "DEVICE", d.getDeviceCode(), AuditResult.SUCCESS,
                Map.of("ownerId", ownerId, "name", d.getName()));
        return toDto(user, d);
    }

    @Transactional
    public DeviceDto update(AuthUser user, String code, UpdateDeviceRequest req) {
        Device d = loadForManage(user, code);
        requireNotDecommissioned(d);
        d.setName(req.name().trim());
        if (req.deviceType() != null && !req.deviceType().isBlank()) {
            d.setDeviceType(req.deviceType().trim());
        }
        d.setLocation(blankToNull(req.location()));
        d.setZone(blankToNull(req.zone()));
        d.setHwVersion(blankToNull(req.hwVersion()));
        audit.log(user.id(), user.username(), "DEVICE_UPDATE", "DEVICE", code, AuditResult.SUCCESS, null);
        return toDto(user, d);
    }

    // ====================================================================== 2. Cấp / đổi thông tin xác thực
    /** REGISTERED -> PROVISIONED: tạo tài khoản MQTT riêng cho thiết bị. */
    @Transactional
    public ProvisionResponse provision(AuthUser user, String code) {
        Device d = loadForManage(user, code);
        if (d.getLifecycleStatus() != LifecycleStatus.REGISTERED) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_PROVISIONED",
                    "Thiết bị đã được cấp thông tin xác thực. Dùng 'đổi mật khẩu' (ROTATE_CREDENTIALS) nếu cần cấp lại");
        }
        String password = issueCredential(user, d, "PROVISION");
        LifecycleStatus from = d.getLifecycleStatus();
        d.setLifecycleStatus(LifecycleStatus.PROVISIONED);
        dynsec.createDeviceAccount(code, password, false);
        audit.log(user.id(), user.username(), "DEVICE_PROVISION", "DEVICE", code, AuditResult.SUCCESS, null);
        events.publishEvent(new DeviceLifecycleChangedEvent(d.getId(), code, from, d.getLifecycleStatus(),
                user.username(), Instant.now()));
        return provisionResponse(code, password);
    }

    /** Cấp mật khẩu mới, thu hồi mật khẩu cũ. Phiên đang kết nối bằng mật khẩu cũ bị ngắt ngay. */
    @Transactional
    public ProvisionResponse rotateCredentials(AuthUser user, String code) {
        Device d = loadForManage(user, code);
        if (d.getLifecycleStatus() == LifecycleStatus.REGISTERED
                || d.getLifecycleStatus() == LifecycleStatus.DECOMMISSIONED) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE",
                    "Không thể đổi mật khẩu khi thiết bị đang " + d.getLifecycleStatus());
        }
        String password = issueCredential(user, d, "ROTATE");
        boolean suspended = d.getLifecycleStatus() == LifecycleStatus.SUSPENDED;
        dynsec.createDeviceAccount(code, password, suspended);
        audit.log(user.id(), user.username(), "DEVICE_CREDENTIAL_ROTATE", "DEVICE", code, AuditResult.SUCCESS, null);
        return provisionResponse(code, password);
    }

    @Transactional(readOnly = true)
    public List<CredentialDto> credentialHistory(AuthUser user, String code) {
        Device d = loadForManage(user, code);
        return credentials.findByDeviceIdOrderByIssuedAtDesc(d.getId()).stream().map(CredentialDto::from).toList();
    }

    /** Thu hồi credential cũ (nếu có), lưu credential mới (chỉ SHA-256), trả mật khẩu thô 1 lần. */
    private String issueCredential(AuthUser user, Device d, String reason) {
        Instant now = Instant.now();
        credentials.findFirstByDeviceIdAndStatusNot(d.getId(), CredentialStatus.REVOKED).ifPresent(old -> {
            old.setStatus(CredentialStatus.REVOKED);
            old.setRevokedAt(now);
            old.setRevokedReason(reason.equals("ROTATE") ? "Đã cấp mật khẩu mới" : reason);
            // flush ngay: chỉ mục uq_credential_live cho phép tối đa 1 credential chưa thu hồi / thiết bị,
            // mà Hibernate mặc định INSERT trước UPDATE khi flush.
            credentials.saveAndFlush(old);
        });
        String password = generatePassword();
        DeviceCredential c = new DeviceCredential();
        c.setDeviceId(d.getId());
        c.setMqttUsername(d.getDeviceCode());
        c.setClientId(d.getDeviceCode());
        c.setSecretFingerprint(sha256(password));
        c.setStatus(d.getLifecycleStatus() == LifecycleStatus.SUSPENDED ? CredentialStatus.DISABLED : CredentialStatus.ACTIVE);
        c.setIssuedAt(now);
        c.setIssuedBy(user.id());
        credentials.save(c);
        return password;
    }

    // ====================================================================== 4, 8, 9, 10. Vòng đời
    @Transactional
    public DeviceDto changeLifecycle(AuthUser user, String code, LifecycleRequest req) {
        Device d = loadForManage(user, code);
        LifecycleAction action = req.action();
        String reason = blankToNull(req.reason());
        if ((action == LifecycleAction.SUSPEND || action == LifecycleAction.DECOMMISSION) && reason == null) {
            throw ApiException.badRequest("REASON_REQUIRED", "Cần nhập lý do khi tạm khóa hoặc ngừng sử dụng thiết bị");
        }
        LifecycleStatus from = d.getLifecycleStatus();
        LifecycleStatus to = DeviceLifecycle.next(from, action);       // ném 409 nếu không hợp lệ
        Instant now = Instant.now();
        d.setLifecycleStatus(to);

        switch (action) {
            case SUSPEND -> {
                setCredentialStatus(d, CredentialStatus.DISABLED, null);
                dynsec.disableDevice(code);                             // broker ngắt kết nối NGAY
                markOffline(d, "SUSPENDED", now);
            }
            case RESUME -> {
                setCredentialStatus(d, CredentialStatus.ACTIVE, null);
                dynsec.enableDevice(code);
            }
            case DECOMMISSION -> {
                d.setDecommissionedAt(now);
                setCredentialStatus(d, CredentialStatus.REVOKED, reason);
                if (from != LifecycleStatus.REGISTERED) {
                    dynsec.deleteDeviceAccount(code);                   // thu hồi vĩnh viễn
                    clearRetainedConfig(code);
                }
                markOffline(d, "DECOMMISSIONED", now);
            }
            default -> {
                // ACTIVATE, START_MAINTENANCE, END_MAINTENANCE: chỉ đổi trạng thái nghiệp vụ.
                // MAINTENANCE: vẫn nhận dữ liệu nhưng Bước 9 sẽ không sinh cảnh báo ngưỡng.
            }
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", action.name());
        details.put("from", from.name());
        details.put("to", to.name());
        if (reason != null) {
            details.put("reason", reason);
        }
        audit.log(user.id(), user.username(), "DEVICE_LIFECYCLE", "DEVICE", code, AuditResult.SUCCESS, details);
        events.publishEvent(new DeviceLifecycleChangedEvent(d.getId(), code, from, to, user.username(), now));
        return toDto(user, d);
    }

    private void setCredentialStatus(Device d, CredentialStatus status, String reason) {
        credentials.findFirstByDeviceIdAndStatusNot(d.getId(), CredentialStatus.REVOKED).ifPresent(c -> {
            c.setStatus(status);
            if (status == CredentialStatus.REVOKED) {
                c.setRevokedAt(Instant.now());
                c.setRevokedReason(reason);
            }
        });
    }

    private void markOffline(Device d, String why, Instant now) {
        Connectivity before = d.getConnectivity();
        if (before != Connectivity.OFFLINE) {
            d.setConnectivity(Connectivity.OFFLINE);
            events.publishEvent(new ConnectivityChangedEvent(d.getId(), d.getDeviceCode(), before,
                    Connectivity.OFFLINE, why, now));
        }
    }

    /** Xóa cấu hình retained trên broker để thiết bị cũ (nếu còn) không nhận lại cấu hình. */
    private void clearRetainedConfig(String code) {
        try {
            gateway.publish(MqttTopics.of(code, MqttTopics.CONFIG), new byte[0], 1, true);
        } catch (MqttException e) {
            log.warn("[{}] Không xóa được retained config: {}", code, e.getMessage());
        }
    }

    // ====================================================================== Chia sẻ quyền xem
    @Transactional(readOnly = true)
    public List<ShareDto> listShares(AuthUser user, String code) {
        Device d = loadForManage(user, code);
        List<DeviceShare> list = shares.findByDeviceId(d.getId());
        Map<Long, User> byId = users.findAllById(list.stream().map(DeviceShare::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return list.stream().map(s -> {
            User u = byId.get(s.getUserId());
            return new ShareDto(s.getUserId(), u == null ? null : u.getUsername(), u == null ? null : u.getFullName(),
                    u == null ? null : u.getRole(), s.getPermission(), s.getCreatedAt());
        }).toList();
    }

    @Transactional
    public ShareDto addShare(AuthUser user, String code, String username) {
        Device d = loadForManage(user, code);
        requireNotDecommissioned(d);
        User target = users.findByUsername(username.trim())
                .orElseThrow(() -> ApiException.notFound("Không tìm thấy người dùng '" + username + "'"));
        if (target.getId().equals(d.getOwnerId())) {
            throw ApiException.badRequest("ALREADY_OWNER", "Người này đã là chủ sở hữu thiết bị");
        }
        if (shares.existsByDeviceIdAndUserId(d.getId(), target.getId())) {
            throw ApiException.conflict("ALREADY_SHARED", "Đã chia sẻ cho người này rồi");
        }
        DeviceShare s = shares.saveAndFlush(new DeviceShare(d.getId(), target.getId()));
        audit.log(user.id(), user.username(), "DEVICE_SHARE_ADD", "DEVICE", code, AuditResult.SUCCESS,
                Map.of("username", target.getUsername()));
        return new ShareDto(target.getId(), target.getUsername(), target.getFullName(), target.getRole(),
                s.getPermission(), s.getCreatedAt());
    }

    @Transactional
    public void removeShare(AuthUser user, String code, String username) {
        Device d = loadForManage(user, code);
        User target = users.findByUsername(username)
                .orElseThrow(() -> ApiException.notFound("Không tìm thấy người dùng '" + username + "'"));
        if (!shares.existsByDeviceIdAndUserId(d.getId(), target.getId())) {
            throw ApiException.notFound("Thiết bị chưa được chia sẻ cho người này");
        }
        shares.deleteByDeviceIdAndUserId(d.getId(), target.getId());
        audit.log(user.id(), user.username(), "DEVICE_SHARE_REMOVE", "DEVICE", code, AuditResult.SUCCESS,
                Map.of("username", target.getUsername()));
    }

    // ====================================================================== Tiện ích
    private Device load(String code) {
        return devices.findByDeviceCode(code)
                .orElseThrow(() -> ApiException.notFound("Không tìm thấy thiết bị '" + code + "'"));
    }

    private static void requireNotDecommissioned(Device d) {
        if (d.getLifecycleStatus() == LifecycleStatus.DECOMMISSIONED) {
            throw new ApiException(HttpStatus.CONFLICT, "DECOMMISSIONED", "Thiết bị đã ngừng sử dụng (chỉ xem lịch sử)");
        }
    }

    private DeviceDto toDto(AuthUser user, Device d) {
        String owner = users.findById(d.getOwnerId()).map(User::getUsername).orElse(null);
        return toDto(user, d, owner);
    }

    private DeviceDto toDto(AuthUser user, Device d, String ownerUsername) {
        return DeviceDto.from(d, ownerUsername, access.canManage(user, d),
                DeviceLifecycle.allowedActions(d.getLifecycleStatus()));
    }

    private ProvisionResponse provisionResponse(String code, String password) {
        Map<String, String> topics = new LinkedHashMap<>();
        for (String ch : List.of(MqttTopics.TELEMETRY, MqttTopics.HEARTBEAT, MqttTopics.STATUS, MqttTopics.EVENT,
                MqttTopics.CMD, MqttTopics.CMD_ACK, MqttTopics.CONFIG, MqttTopics.CONFIG_ACK)) {
            topics.put(ch, MqttTopics.of(code, ch));
        }
        return new ProvisionResponse(code, deviceMqttUrl, code, code, password, topics,
                "Mật khẩu chỉ hiển thị MỘT LẦN. Hãy nạp vào firmware (secrets.h) ngay; nếu mất phải cấp lại.");
    }

    /** 18 byte ngẫu nhiên mật mã học -> 24 ký tự base64url. */
    static String generatePassword() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
