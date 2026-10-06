package com.aireshub.auth;

import com.aireshub.common.AuthUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 从 Authorization: Bearer &lt;token&gt; 解析 JWT,
 * 校验通过后把 AuthUser 放入 SecurityContext。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final SupabaseUserProvisioner provisioner;

    public JwtAuthFilter(JwtService jwtService,
                         UserRepository userRepository,
                         SupabaseUserProvisioner provisioner) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.provisioner = provisioner;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtService.parse(token);
                UUID userId = UUID.fromString(claims.getSubject());
                UserEntity user = userRepository.findById(userId).orElse(null);
                if (user == null && jwtService.isSupabase()) {
                    String email = claims.get("email", String.class);
                    if (email != null && !email.isBlank()) {
                        try {
                            user = provisioner.ensure(userId, email.trim().toLowerCase());
                        } catch (RuntimeException ex) {
                            user = null;
                        }
                    }
                }
                if (user != null) {
                    AuthUser principal = new AuthUser(user.getId(), user.getEmail(), user.getDisplayName());
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of());
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (JwtException | IllegalArgumentException ignored) {
                // 无效 token:保持未认证状态,由授权规则返回 401
            }
        }
        filterChain.doFilter(request, response);
    }
}
