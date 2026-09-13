package com.samanvay.connector.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SharedResilienceInstanceTest {

    @Test
    void twoConnectorsShareBreakerForSameDataSource() {
        ResilienceRegistries registries = new ResilienceRegistries();
        assertThat(registries.breaker("revenue-rest-mock")).isSameAs(registries.breaker("revenue-rest-mock"));
    }
}
