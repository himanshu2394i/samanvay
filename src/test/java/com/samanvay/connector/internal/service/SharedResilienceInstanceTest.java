package com.samanvay.connector.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SharedResilienceInstanceTest {

    @Test
    void twoConnectorsShareBreakerForSameDataSource() {
        ResilienceRegistries registries = new ResilienceRegistries();
        assertThat(registries.breaker("revenue-rest-mock")).isSameAs(registries.breaker("revenue-rest-mock"));
    }

    @Test
    void executeDecoratesWithSharedBreaker() {
        ResilienceRegistries registries = new ResilienceRegistries();
        assertThat(registries.execute("revenue-rest-mock", () -> "ok")).isEqualTo("ok");
        assertThat(registries.breaker("revenue-rest-mock").getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
        assertThat(registries.retry("revenue-rest-mock").getMetrics().getNumberOfSuccessfulCallsWithoutRetryAttempt())
                .isEqualTo(1);
    }
}
