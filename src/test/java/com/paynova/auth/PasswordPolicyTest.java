package com.paynova.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"abcd1234", "Passw0rd!", "12345678a"})
    void acceptsLettersAndDigitsMin8(String password) {
        assertThat(AuthService.PASSWORD_POLICY.matcher(password).matches()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"short1", "onlyletters", "12345678", ""})
    void rejectsWeakPasswords(String password) {
        assertThat(AuthService.PASSWORD_POLICY.matcher(password).matches()).isFalse();
    }
}
