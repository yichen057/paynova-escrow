package com.paynova.idempotency;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests for canonical_json stability: semantically identical requests must produce the same hash (§8). */
class RequestHashTest {

    record Sample(Long amount_cents, String note) {}

    @Test
    void sameBodySameHash() {
        assertThat(RequestHash.of("POST", "/api/x", new Sample(100L, "a")))
                .isEqualTo(RequestHash.of("POST", "/api/x", new Sample(100L, "a")))
                .hasSize(64);   // SHA-256 hex
    }

    @Test
    void mapKeyInsertionOrderDoesNotMatter() {
        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("a", 1);
        ab.put("b", 2);
        Map<String, Object> ba = new LinkedHashMap<>();
        ba.put("b", 2);
        ba.put("a", 1);

        assertThat(RequestHash.of("POST", "/api/x", ab))
                .isEqualTo(RequestHash.of("POST", "/api/x", ba));
    }

    @Test
    void differentBodyMethodOrPathChangesHash() {
        String base = RequestHash.of("POST", "/api/x", new Sample(100L, "a"));

        assertThat(RequestHash.of("POST", "/api/x", new Sample(200L, "a"))).isNotEqualTo(base);
        assertThat(RequestHash.of("PUT", "/api/x", new Sample(100L, "a"))).isNotEqualTo(base);
        assertThat(RequestHash.of("POST", "/api/y", new Sample(100L, "a"))).isNotEqualTo(base);
    }
}
