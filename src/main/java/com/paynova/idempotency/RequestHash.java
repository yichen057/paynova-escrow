package com.paynova.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * request_hash = SHA-256(method + path + canonical_json(body)) (design doc §8).
 * Canonicalization rules: UTF-8, fields in lexicographic order, normalized numbers —
 * semantically identical requests must produce the same hash, otherwise
 * "same key, same request" would be misclassified as "same key, different request" -> 409.
 */
public final class RequestHash {

    /** Deterministic mapper independent of Spring: sorted Map keys + alphabetical bean properties. */
    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private RequestHash() {
    }

    public static String of(String method, String path, Object body) {
        try {
            String canonicalBody = CANONICAL.writeValueAsString(body);
            String material = method + "\n" + path + "\n" + canonicalBody;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("failed to compute request hash", e);
        }
    }
}
