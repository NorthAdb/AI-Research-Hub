package com.aireshub.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * JWT 签发与校验。
 *
 * <p>Demo 阶段由本服务自签发 token(替代 Supabase Auth 的 JWT);
 * 接入云端 Supabase 后,只需把这里的 verifyWith 换成 Supabase 的
 * JWKS / HMAC secret,签发逻辑交回 Supabase Auth,见 docs/decisions.md。</p>
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final SecretKey supabaseKey;
    private final boolean supabase;
    private final long ttlHours;

    /** 本地签发。测试和默认 Demo 用这个。 */
    public JwtService(String secret, long ttlHours) {
        this(secret, ttlHours, "local", "");
    }

    @Autowired
    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.ttl-hours}") long ttlHours,
                      @Value("${app.auth.issuer:local}") String issuer,
                      @Value("${app.auth.supabase-jwt-secret:}") String supabaseSecret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlHours = ttlHours;
        this.supabase = "supabase".equalsIgnoreCase(issuer);
        if (this.supabase) {
            byte[] bytes = supabaseSecret == null ? new byte[0] : supabaseSecret.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 32) {
                throw new IllegalStateException(
                        "AUTH_ISSUER=supabase 但 SUPABASE_JWT_SECRET 缺失或短于 32 字节。不接云端时请保持 AUTH_ISSUER=local。");
            }
            this.supabaseKey = Keys.hmacShaKeyFor(bytes);
        } else {
            this.supabaseKey = null;
        }
    }

    public boolean isSupabase() {
        return supabase;
    }

    public String issue(UUID userId, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttlHours, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    /**
     * 校验签名与有效期,返回 claims;失败抛 JwtException。
     */
    public Claims parse(String token) throws JwtException {
        SecretKey active = supabase ? supabaseKey : key;
        return Jwts.parser()
                .verifyWith(active)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
