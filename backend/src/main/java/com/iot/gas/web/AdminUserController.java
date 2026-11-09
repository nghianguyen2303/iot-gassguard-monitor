package com.iot.gas.web;

import com.iot.gas.security.CurrentUser;
import com.iot.gas.service.UserService;
import com.iot.gas.web.dto.CreateUserRequest;
import com.iot.gas.web.dto.UpdateRoleRequest;
import com.iot.gas.web.dto.UserDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Chỉ ADMIN. Kiểm tra 2 lớp: theo URL (/api/admin/**) trong SecurityConfig và @PreAuthorize ở đây. */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;

    @GetMapping
    public List<UserDto> list() {
        return userService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto create(@Valid @RequestBody CreateUserRequest req) {
        return userService.create(CurrentUser.require(), req);
    }

    @PutMapping("/{id}/role")
    public UserDto changeRole(@PathVariable Long id, @Valid @RequestBody UpdateRoleRequest req) {
        return userService.changeRole(CurrentUser.require(), id, req.role());
    }

    @PostMapping("/{id}/lock")
    public UserDto lock(@PathVariable Long id) {
        return userService.lock(CurrentUser.require(), id);
    }

    @PostMapping("/{id}/unlock")
    public UserDto unlock(@PathVariable Long id) {
        return userService.unlock(CurrentUser.require(), id);
    }
}
