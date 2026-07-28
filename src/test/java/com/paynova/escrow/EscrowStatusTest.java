package com.paynova.escrow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full coverage of the state-transition matrix (design doc §6): 4 states x 3 actions = 12 combinations,
 * 3 legal and 9 illegal -- each asserted individually, so this test acts as the regression
 * safety net whenever the transition rules change.
 */
class EscrowStatusTest {

    @ParameterizedTest
    @CsvSource({
            "CREATED, FUND,    FUNDED",
            "FUNDED,  RELEASE, RELEASED",
            "FUNDED,  REFUND,  REFUNDED",
    })
    void legalTransitions(EscrowStatus from, EscrowAction action, EscrowStatus expected) {
        assertThat(from.nextFor(action)).contains(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "CREATED,  RELEASE",
            "CREATED,  REFUND",
            "FUNDED,   FUND",
            "RELEASED, FUND",
            "RELEASED, RELEASE",
            "RELEASED, REFUND",
            "REFUNDED, FUND",
            "REFUNDED, RELEASE",
            "REFUNDED, REFUND",
    })
    void illegalTransitions(EscrowStatus from, EscrowAction action) {
        assertThat(from.nextFor(action)).isEmpty();
    }

    @Test
    void terminalStates() {
        assertThat(EscrowStatus.RELEASED.isTerminal()).isTrue();
        assertThat(EscrowStatus.REFUNDED.isTerminal()).isTrue();
        assertThat(EscrowStatus.CREATED.isTerminal()).isFalse();
        assertThat(EscrowStatus.FUNDED.isTerminal()).isFalse();
    }
}
