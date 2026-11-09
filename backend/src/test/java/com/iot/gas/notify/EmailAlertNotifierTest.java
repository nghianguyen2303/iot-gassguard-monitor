package com.iot.gas.notify;

import com.iot.gas.domain.Device;
import com.iot.gas.domain.enums.AlertStatus;
import com.iot.gas.domain.enums.AlertType;
import com.iot.gas.domain.enums.Severity;
import com.iot.gas.service.event.AlertChangedEvent;
import com.iot.gas.web.dto.AlertDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Quy tắc chống spam email cảnh báo: mỗi sự cố tối đa 1 email khi xảy ra + 1 email khi trở lại bình thường. */
class EmailAlertNotifierTest {

    private static final EmailClient GMAIL = new EmailClient("smtp.gmail.com", 587, "u@gmail.com", "app-pw", "",
            "GasGuard", true, true, false);

    private static AlertDto alert(long id, String code, AlertType type) {
        return new AlertDto(id, 1L, code, "Bếp", null, null, type, Severity.WARNING, "msg", null, null,
                AlertStatus.OPEN, 1, Instant.now(), Instant.now(), null, null, null, null, null, null, true);
    }

    @Test
    void offlineSendsOnePairOnlyUntilResolved() {
        EmailAlertNotifier n = new EmailAlertNotifier(GMAIL, null, null, "", "e2e-,perf-");
        AlertDto a = alert(10, "esp32-001", AlertType.OFFLINE);
        assertTrue(n.shouldEmail("CREATED", a), "mất kết nối -> gửi");
        assertTrue(n.shouldEmail("CLEARED", a), "kết nối lại -> gửi");
        assertFalse(n.shouldEmail("RECURRED", a), "chập chờn: mất lại -> không gửi");
        assertFalse(n.shouldEmail("CLEARED", a), "chập chờn: kết nối lại lần 2 -> không gửi");
        assertFalse(n.shouldEmail("RESOLVED", a), "đã xử lý xong -> không gửi");

        n.onAlertChanged(new AlertChangedEvent(1L, "RESOLVED", a));
        AlertDto next = alert(11, "esp32-001", AlertType.OFFLINE);
        assertTrue(n.shouldEmail("CREATED", next) && n.shouldEmail("CLEARED", next), "sự cố mới -> lại 1 cặp email");
    }

    @Test
    void thresholdSendsOnceWhenRaisedAndOnceWhenCleared() {
        EmailAlertNotifier n = new EmailAlertNotifier(GMAIL, null, null, "", "e2e-,perf-");
        AlertDto gas = alert(12, "esp32-001", AlertType.THRESHOLD);
        assertTrue(n.shouldEmail("CREATED", gas));
        assertTrue(n.shouldEmail("CLEARED", gas));
        assertFalse(n.shouldEmail("RECURRED", gas));
        assertFalse(n.shouldEmail("CLEARED", gas));
    }

    @Test
    void automatedTestDevicesNeverEmail() {
        EmailAlertNotifier n = new EmailAlertNotifier(GMAIL, null, null, "", "e2e-,perf-");
        assertFalse(n.shouldEmail("CREATED", alert(13, "perf-1107-5", AlertType.OFFLINE)));
        assertFalse(n.shouldEmail("CREATED", alert(14, "e2e-1107", AlertType.THRESHOLD)));
        EmailAlertNotifier all = new EmailAlertNotifier(GMAIL, null, null, "", "");
        assertTrue(all.shouldEmail("CREATED", alert(15, "perf-1", AlertType.OFFLINE)), "danh sách rỗng -> gửi mọi thiết bị");
    }

    @Test
    void disabledWhenGmailNotConfigured() {
        EmailClient none = new EmailClient("smtp.gmail.com", 587, "", "", "", "GasGuard", true, true, false);
        assertFalse(new EmailAlertNotifier(none, null, null, "", "e2e-").shouldEmail("CREATED",
                alert(16, "esp32-001", AlertType.OFFLINE)));
    }

    @Test
    void reconnectSubjectIsClear() {
        EmailAlertNotifier n = new EmailAlertNotifier(GMAIL, null, null, "", "");
        Device d = new Device();
        d.setName("Bếp");
        d.setDeviceCode("esp32-001");
        assertTrue(n.compose("CLEARED", alert(17, "esp32-001", AlertType.OFFLINE), d).subject().contains("Đã kết nối lại"));
    }
}
