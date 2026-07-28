package com.paynova.ledger;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit tests for invariant #1 (no database): entry-point validation is the ledger's first line of defense. */
class LedgerValidationTest {

    @Test
    void balancedTwoEntryTransactionPasses() {
        assertThatCode(() -> LedgerService.validate(List.of(
                Posting.debit(1L, 100),
                Posting.credit(2L, 100)))).doesNotThrowAnyException();
    }

    @Test
    void balancedMultiEntryTransactionPasses() {
        // Future "payment + fee" shape: 1 debit, 2 credits -- the posting-list API needs no interface change
        assertThatCode(() -> LedgerService.validate(List.of(
                Posting.debit(1L, 100),
                Posting.credit(2L, 97),
                Posting.credit(3L, 3)))).doesNotThrowAnyException();
    }

    @Test
    void rejectsSingleEntry() {
        assertThatThrownBy(() -> LedgerService.validate(List.of(Posting.debit(1L, 100))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 2");
    }

    @Test
    void rejectsUnbalanced() {
        assertThatThrownBy(() -> LedgerService.validate(List.of(
                Posting.debit(1L, 100),
                Posting.credit(2L, 99))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unbalanced");
    }

    @Test
    void rejectsNonPositiveAmounts() {
        assertThatThrownBy(() -> LedgerService.validate(List.of(
                Posting.debit(1L, 0),
                Posting.credit(2L, 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }
}
