package com.paynova.auth;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests; no database involved (see design doc §12 for the test layering). */
class JwtServiceTest {

    private static final String SECRET =
            Base64.getEncoder().encodeToString("unit-test-secret-key-32-bytes-long!!".getBytes());

    private final JwtService jwtService = new JwtService(SECRET, 60);

    @Test
    void generateAndParseRoundTrip() {
        String token = jwtService.generate(42L, "alice@example.com", User.Role.USER);

        Optional<Claims> claims = jwtService.parse(token);

        assertThat(claims).isPresent();
        assertThat(claims.get().getSubject()).isEqualTo("42");
        assertThat(claims.get().get("email", String.class)).isEqualTo("alice@example.com");
        assertThat(claims.get().get("role", String.class)).isEqualTo("USER");
        assertThat(claims.get().getExpiration()).isAfter(claims.get().getIssuedAt());
    }

    @Test
    void parseRejectsGarbageToken() {
        assertThat(jwtService.parse("not-a-jwt")).isEmpty();
    }

    @Test
    void parseRejectsTokenSignedWithDifferentKey() {
        String otherSecret = Base64.getEncoder()
                .encodeToString("another-secret-key-32-bytes-long!!!!".getBytes());
        String token = new JwtService(otherSecret, 60).generate(1L, "a@b.com", User.Role.USER);

        assertThat(jwtService.parse(token)).isEmpty();
    }
}
