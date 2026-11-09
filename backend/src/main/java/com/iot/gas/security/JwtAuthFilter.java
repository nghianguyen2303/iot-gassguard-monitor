package com.iot.gas.security;

import com.iot.gas.domain.User;
import com.iot.gas.domain.enums.UserStatus;
import com.iot.gas.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Đọc header "Authorization: Bearer <token>", kiểm tra chữ ký + hạn + token_version + trạng thái tài khoản.
 * Vai trò lấy từ DB (không tin claim trong token) -> đổi vai trò có hiệu lực ngay.
 * Nếu token sai: không xác thực, ghi lý do vào request để RestAuthEntryPoint trả 401 rõ ràng.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String AUTH_ERROR_ATTR = "auth_error";

    private final JwtService jwtService;
    private final UserRepository users;

    public JwtAuthFilter(JwtService jwtService, UserRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            authenticate(header.substring(7).trim(), request);
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        try {
            Claims claims = jwtService.parse(token);
            long userId = ((Number) claims.get("uid")).longValue();
            int version = ((Number) claims.get("ver")).intValue();

            Optional<User> found = users.findById(userId);
            if (found.isEmpty()) {
                request.setAttribute(AUTH_ERROR_ATTR, "Tài khoản không tồn tại");
                return;
            }
            User user = found.get();
            if (user.getStatus() != UserStatus.ACTIVE) {
                request.setAttribute(AUTH_ERROR_ATTR, "Tài khoản đã bị khóa");
                return;
            }
            if (user.getTokenVersion() != version) {
                request.setAttribute(AUTH_ERROR_ATTR, "Token đã bị thu hồi (đã đăng xuất, đổi mật khẩu hoặc đổi quyền)");
                return;
            }
            AuthUser principal = new AuthUser(user.getId(), user.getUsername(), user.getRole());
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (ExpiredJwtException e) {
            request.setAttribute(AUTH_ERROR_ATTR, "Token đã hết hạn");
        } catch (JwtException | IllegalArgumentException | ClassCastException | NullPointerException e) {
            request.setAttribute(AUTH_ERROR_ATTR, "Token không hợp lệ");
        }
    }
}
