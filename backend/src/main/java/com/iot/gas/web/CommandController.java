package com.iot.gas.web;

import com.iot.gas.security.CurrentUser;
import com.iot.gas.service.CommandService;
import com.iot.gas.web.dto.CommandDto;
import com.iot.gas.web.dto.SendCommandRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/devices/{code}/commands")
@RequiredArgsConstructor
public class CommandController {

    private final CommandService commandService;

    /** Gửi lệnh. waitMs (vd. 5000) = chờ ACK rồi mới trả về; 0 = trả về ngay (status SENT). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommandDto send(@PathVariable String code, @Valid @RequestBody SendCommandRequest req,
                           @RequestParam(defaultValue = "0") long waitMs) {
        return commandService.send(CurrentUser.require(), code, req, waitMs);
    }

    @GetMapping
    public List<CommandDto> history(@PathVariable String code, @RequestParam(defaultValue = "50") int limit) {
        return commandService.history(CurrentUser.require(), code, limit);
    }

    @GetMapping("/{id}")
    public CommandDto get(@PathVariable String code, @PathVariable UUID id) {
        return commandService.get(CurrentUser.require(), code, id);
    }
}
