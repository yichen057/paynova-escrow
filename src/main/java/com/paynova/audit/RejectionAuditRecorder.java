package com.paynova.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Failure/rejection audits (design doc §11). Runs in its OWN transaction
 * (REQUIRES_NEW): by the time the global exception handler calls this, the business
 * transaction has already rolled back — without a fresh transaction the audit row
 * would be lost, and the README's "failed attempts are audited" claim would be false.
 *
 * This lives in a separate bean (not a method on AuditService) on purpose:
 * calling a @Transactional method from within the same class bypasses the Spring
 * proxy (self-invocation) and the REQUIRES_NEW semantics would silently not apply.
 */
@Service
public class RejectionAuditRecorder {

    private final AuditService auditService;

    public RejectionAuditRecorder(AuditService auditService) {
        this.auditService = auditService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rejected(String eventType, String details) {
        auditService.writeRow(eventType, null, null, null, null,
                null, null, null, null, "REJECTED", details);
    }
}
