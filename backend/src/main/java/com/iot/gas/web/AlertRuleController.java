package com.iot.gas.web;

import com.iot.gas.alert.AlertRuleService;
import com.iot.gas.security.CurrentUser;
import com.iot.gas.web.dto.AlertRuleDto;
import com.iot.gas.web.dto.AlertRuleRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/alert-rules")
@RequiredArgsConstructor
public class AlertRuleController {

    private final AlertRuleService ruleService;

    @GetMapping
    public List<AlertRuleDto> list(@RequestParam(required = false) String deviceCode) {
        return ruleService.list(CurrentUser.require(), deviceCode);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AlertRuleDto create(@Valid @RequestBody AlertRuleRequest req) {
        return ruleService.create(CurrentUser.require(), req);
    }

    @PutMapping("/{id}")
    public AlertRuleDto update(@PathVariable Long id, @Valid @RequestBody AlertRuleRequest req) {
        return ruleService.update(CurrentUser.require(), id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        ruleService.delete(CurrentUser.require(), id);
    }
}
