package com.paynova.escrow;

import java.util.Optional;

/**
 * Escrow state machine (design doc §6 transition matrix).
 *
 * Design decision (Step 1): transition rules are carried by the enum itself — rules live next to
 * the state definitions, the compiler enforces exhaustive switch coverage when a state is added,
 * and there is no path to change status while bypassing validation.
 *
 *   CREATED --FUND--> FUNDED --RELEASE--> RELEASED (terminal)
 *                            --REFUND--->  REFUNDED (terminal)
 */
public enum EscrowStatus {
    CREATED,
    FUNDED,
    RELEASED,
    REFUNDED;

    /**
     * Target state for executing the given action in this state; returns empty for illegal combinations.
     * This is the first line of defense (business-semantics validation); the second is the database CAS
     * (UPDATE ... WHERE status = old value). Both are indispensable:
     * the former yields clear 409 semantics, the latter guarantees a single winner under concurrency.
     */
    public Optional<EscrowStatus> nextFor(EscrowAction action) {
        return Optional.ofNullable(switch (this) {
            case CREATED -> action == EscrowAction.FUND ? FUNDED : null;
            case FUNDED -> switch (action) {
                case RELEASE -> RELEASED;
                case REFUND -> REFUNDED;
                case FUND -> null;
            };
            case RELEASED, REFUNDED -> null;   // terminal states: every action is illegal
        });
    }

    public boolean isTerminal() {
        return this == RELEASED || this == REFUNDED;
    }
}
