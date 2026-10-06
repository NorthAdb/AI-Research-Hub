package com.aireshub.auth;

import com.aireshub.common.ApiException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw ApiException.conflict("该邮箱已注册");
        }
        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName() == null || request.displayName().isBlank()
                ? email.split("@")[0]
                : request.displayName().trim());
        userRepository.save(user);
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        String email = request.email().trim().toLowerCase();
        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("invalid credentials"));
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("invalid credentials");
        }
        return toResponse(user);
    }

    private AuthDtos.AuthResponse toResponse(UserEntity user) {
        String token = jwtService.issue(user.getId(), user.getEmail());
        AuthDtos.UserView view =
                new AuthDtos.UserView(user.getId(), user.getEmail(), user.getDisplayName());
        return new AuthDtos.AuthResponse(token, view);
    }
}
