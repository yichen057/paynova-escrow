package com.paynova.ledger;

/** Maps one-to-one to the CHECK constraint in V1__init.sql; V1 has exactly four money flows (design doc §5). */
public enum LedgerTransactionType {
    TOP_UP, ESCROW_FUND, ESCROW_RELEASE, ESCROW_REFUND
}
