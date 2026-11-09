package com.iot.gas.web;

import com.iot.gas.security.CurrentUser;
import com.iot.gas.service.DashboardService;
import com.iot.gas.web.dto.DashboardDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboard;

    @GetMapping("/api/dashboard")
    public DashboardDto overview() {
        return dashboard.overview(CurrentUser.require());
    }
}
