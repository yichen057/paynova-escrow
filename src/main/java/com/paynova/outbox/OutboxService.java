package com.paynova.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Outbox write entry point (§10): the event commits in the same transaction as the
 * business change that produced it. MANDATORY propagation enforces "no transaction,
 * no event write" — which is the entire point of the Transactional Outbox pattern:
 * if the state changed the event must exist, if the state rolled back the event must not.
 */
@Service
public class OutboxService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OutboxService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String aggregateType, String aggregateId, String eventType,
                       Map<String, Object> payload) {
        try {
            jdbc.update("""
                    INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
                    VALUES (?, ?, ?, ?::jsonb)
                    """, aggregateType, aggregateId, eventType,
                    objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize outbox payload", e);
        }
    }
}
