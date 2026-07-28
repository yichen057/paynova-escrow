package com.paynova.outbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HmacSignerTest {

    @Test
    void signIsDeterministicAndSecretSensitive() {
        String a = HmacSigner.sign("secret-1", "{\"x\":1}");

        assertThat(a).isEqualTo(HmacSigner.sign("secret-1", "{\"x\":1}")).hasSize(64);
        assertThat(HmacSigner.sign("secret-2", "{\"x\":1}")).isNotEqualTo(a);
        assertThat(HmacSigner.sign("secret-1", "{\"x\":2}")).isNotEqualTo(a);
    }

    @Test
    void verifyAcceptsCorrectAndRejectsWrongSignature() {
        String payload = "{\"order\":\"abc\"}";
        String good = HmacSigner.sign("s", payload);

        assertThat(HmacSigner.verify("s", payload, good)).isTrue();
        assertThat(HmacSigner.verify("s", payload, "deadbeef")).isFalse();
        assertThat(HmacSigner.verify("s", payload, null)).isFalse();
        assertThat(HmacSigner.verify("other", payload, good)).isFalse();
    }
}
