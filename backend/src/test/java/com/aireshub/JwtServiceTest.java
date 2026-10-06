package com.aireshub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aireshub.auth.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 纯单元测试(不依赖 Spring 上下文 / 数据库),保证 `mvn test` 可独立运行。
 */
class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-key-0123456789abcdef0123456789abcdef";

    @Test
    void issueThenParseReturnsSameSubject() {
        JwtService jwtService = new JwtService(SECRET, 1);
        UUID userId = UUID.randomUUID();

        String token = jwtService.issue(userId, "alice@example.com");
        Claims claims = jwtService.parse(token);

        assertEquals(userId.toString(), claims.getSubject());
        assertEquals("alice@example.com", claims.get("email", String.class));
        assertNotNull(claims.getExpiration());
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtService jwtService = new JwtService(SECRET, 1);
        String token = jwtService.issue(UUID.randomUUID(), "bob@example.com");

        String tampered = token.substring(0, token.length() - 3) + "xxx";
        assertThrows(JwtException.class, () -> jwtService.parse(tampered));
    }

    @Test
    void wrongSecretIsRejected() {
        JwtService signer = new JwtService(SECRET, 1);
        JwtService verifier = new JwtService("another-secret-key-0123456789abcdef0123456789ab", 1);

        String token = signer.issue(UUID.randomUUID(), "carol@example.com");
        assertThrows(JwtException.class, () -> verifier.parse(token));
    }

    @Test
    void supabaseModeAcceptsOnlyTheSupabaseSecret() {
        String supabaseSecret = "supabase-project-jwt-secret-0123456789abcdef";
        JwtService verifier = new JwtService(SECRET, 1, "supabase", supabaseSecret);
        UUID userId = UUID.randomUUID();
        String token = Jwts.builder()
                .subject(userId.toString())
                .claim("email", "ada@example.com")
                .signWith(Keys.hmacShaKeyFor(supabaseSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();

        Claims claims = verifier.parse(token);
        assertEquals(userId.toString(), claims.getSubject());
        assertEquals("ada@example.com", claims.get("email", String.class));

        String localToken = new JwtService(SECRET, 1).issue(userId, "ada@example.com");
        assertThrows(JwtException.class, () -> verifier.parse(localToken));
    }

    @Test
    void supabaseModeRejectsAShortSecret() {
        assertThrows(IllegalStateException.class,
                () -> new JwtService(SECRET, 1, "supabase", "too-short"));
    }
}
