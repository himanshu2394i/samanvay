package com.samanvay.consent.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GrantVerifierNeverHoldsPrivateKeyTest {

    @Test
    void noPrivateKeyField() {
        assertThat(Ed25519GrantVerifier.fieldIsNeverPrivateKey()).isTrue();
    }
}
