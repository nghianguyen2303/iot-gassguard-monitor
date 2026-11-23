package com.iot.gas.service;

import com.iot.gas.domain.Device;
import com.iot.gas.domain.enums.Role;
import com.iot.gas.repository.DeviceShareRepository;
import com.iot.gas.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Kiểm tra quyền trên TỪNG thiết bị (mục 4.5: chỉ truy cập thiết bị/dữ liệu trong phạm vi được cấp).
 * <ul>
 *   <li>Xem: ADMIN, chủ sở hữu, hoặc người được chia sẻ.</li>
 *   <li>Điều khiển (bật/tắt quạt, còi; xác nhận, xử lý cảnh báo): ADMIN, hoặc tài khoản vai trò OWNER
 *       là chủ thiết bị hoặc được chia sẻ thiết bị. VIEWER luôn chỉ xem.</li>
 *   <li>Quản lý (cấu hình, vòng đời, xác thực MQTT, chia sẻ): ADMIN, hoặc chủ sở hữu có vai trò OWNER.</li>
 * </ul>
 * Vi phạm -> AccessDeniedException -> 403 + audit DENIED (GlobalExceptionHandler).
 */
@Service
@RequiredArgsConstructor
public class AccessService {

    private final DeviceShareRepository shares;

    public boolean canView(AuthUser user, Device device) {
        return user.isAdmin()
                || device.getOwnerId().equals(user.id())
                || shares.existsByDeviceIdAndUserId(device.getId(), user.id());
    }

    public boolean canManage(AuthUser user, Device device) {
        return user.isAdmin() || (user.role() == Role.OWNER && device.getOwnerId().equals(user.id()));
    }

    public boolean canControl(AuthUser user, Device device) {
        return user.isAdmin() || (user.role() == Role.OWNER && canView(user, device));
    }

    public void requireControl(AuthUser user, Device device) {
        if (!canControl(user, device)) {
            throw new AccessDeniedException("Không có quyền điều khiển thiết bị " + device.getDeviceCode());
        }
    }

    public void requireView(AuthUser user, Device device) {
        if (!canView(user, device)) {
            throw new AccessDeniedException("Không có quyền xem thiết bị " + device.getDeviceCode());
        }
    }

    public void requireManage(AuthUser user, Device device) {
        if (!canManage(user, device)) {
            throw new AccessDeniedException("Không có quyền quản lý thiết bị " + device.getDeviceCode());
        }
    }
}
