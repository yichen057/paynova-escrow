package com.paynova.ledger;

/**
 * A single entry to be posted (Formance posting concept): one business operation =
 * N postings applied atomically.
 * Amounts are always positive; the sign is carried by direction (matching the
 * ledger_entries table convention).
 */
public record Posting(Long accountId, Direction direction, long amountCents) {

    public static Posting debit(Long accountId, long amountCents) {
        return new Posting(accountId, Direction.DEBIT, amountCents);
    }

    public static Posting credit(Long accountId, long amountCents) {
        return new Posting(accountId, Direction.CREDIT, amountCents);
    }
}
