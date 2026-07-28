package com.paynova.audit;

import com.paynova.common.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Audit trail (design doc §11). Two write paths with different transaction semantics:
 *
 *  - success(...): Propagation.MANDATORY — a success audit must live and die with the
 *    business transaction that produced it (state changed => audit exists; rolled back
 *    => audit vanishes too).
 *  - Rejection/failure audits go through {@link RejectionAuditRecorder} (a separate bean
 *    with REQUIRES_NEW) — the business transaction has already rolled back by then, so
 *    the audit row needs its own transaction to survive. A separate bean is mandatory:
 *    self-invocation would silently bypass the transactional proxy.
 *
 * Every audit row is mirrored as a structured JSON log line (SIEM-ready; the "json"
 * Spring profile switches the console to LogstashEncoder). Red line: never log JWTs,
 * passwords, Authorization headers, or card data.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger("AUDIT");

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Success audit: joins (and requires) the caller's business transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void success(String eventType, Long actorId, String actorRole,
                        UUID escrowId, UUID ledgerTransactionId,
                        String oldStatus, String newStatus,
                        Long amountCents, String currency, String details) {
        writeRow(eventType, actorId, actorRole, escrowId, ledgerTransactionId,
                oldStatus, newStatus, amountCents, currency, "SUCCESS", details);
    }

    /**
     * Shared row writer — intentionally NOT annotated: it joins whatever transaction
     * the caller (success() or RejectionAuditRecorder) has opened.
     */
    void writeRow(String eventType, Long actorId, String actorRole,
                  UUID escrowId, UUID ledgerTransactionId,
                  String oldStatus, String newStatus,
                  Long amountCents, String currency, String result, String details) {
        Long resolvedActor = actorId != null ? actorId : contextActorId();
        String resolvedRole = actorRole != null ? actorRole : contextActorRole();
        UUID correlationId = CorrelationIdFilter.current();
        String sourceIp = MDC.get(CorrelationIdFilter.MDC_SOURCE_IP);

        jdbc.update("""
                INSERT INTO audit_events
                  (event_type, correlation_id, actor_id, actor_role, escrow_id,
                   ledger_transaction_id, source_ip, old_status, new_status,
                   amount, currency, result, details)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, to_jsonb(?::text))
                """, eventType, correlationId, resolvedActor, resolvedRole, escrowId,
                ledgerTransactionId, sourceIp, oldStatus, newStatus,
                amountCents, currency, result, details);

        // SIEM-ready structured log line (fields become JSON keys under the "json" profile)
        log.info("audit", kv("event_type", eventType), kv("result", result),
                kv("actor_id", resolvedActor), kv("actor_role", resolvedRole),
                kv("escrow_id", escrowId), kv("ledger_transaction_id", ledgerTransactionId),
                kv("amount_cents", amountCents), kv("currency", currency),
                kv("details", details));
    }

    private Long contextActorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getPrincipal() instanceof Long id) ? id : null;
    }

    private String contextActorRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        return auth.getAuthorities().stream().findFirst()
                .map(a -> a.getAuthority().replace("ROLE_", "")).orElse(null);
    }
}
