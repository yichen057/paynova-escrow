package com.paynova.escrow;

/** The three money-movement actions. Money endpoints go live in Step 4; Step 1 covers state semantics only. */
public enum EscrowAction {
    FUND, RELEASE, REFUND
}
