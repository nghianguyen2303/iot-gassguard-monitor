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
 *   <li>Quản lý/điều khiển: ADMIN, hoặc chủ sở hữu có vai trò OWNER.</li>
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
