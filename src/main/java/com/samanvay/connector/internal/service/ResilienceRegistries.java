package com.samanvay.connector.internal.service;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import com.samanvay.connector.internal.protocol.ExchangeDeadlineExceededException;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class ResilienceRegistries {

    private final CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
    private final BulkheadRegistry bulkheads = BulkheadRegistry.ofDefaults();
    private final RetryRegistry retries;

    ResilienceRegistries() {
        this(3, Duration.ofMillis(500));
    }

    /**
     * Retries are counted inside the connector's total deadline ({@code ExchangeDeadline}), and a
     * deadline overrun is never retried: the deadline already spans every attempt.
     */
    @Autowired
    ResilienceRegistries(
            @Value("${samanvay.connector.retry.max-attempts:3}") int maxAttempts,
            @Value("${samanvay.connector.retry.wait:PT0.5S}") Duration wait) {
        this.retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(wait)
                .ignoreExceptions(ExchangeDeadlineExceededException.class, com.samanvay.connector.internal.protocol.ResponseTooLargeException.class)
                .build());
    }

    CircuitBreaker breaker(String dataSourceCode) {
        return breakers.circuitBreaker(dataSourceCode);
    }

    Bulkhead bulkhead(String dataSourceCode) {
        return bulkheads.bulkhead(dataSourceCode);
    }

    Retry retry(String dataSourceCode) {
        return retries.retry(dataSourceCode);
    }

    <T> T execute(String dataSourceCode, Supplier<T> work) {
        Supplier<T> guarded = Bulkhead.decorateSupplier(bulkhead(dataSourceCode), work);
        guarded = CircuitBreaker.decorateSupplier(breaker(dataSourceCode), guarded);
        guarded = Retry.decorateSupplier(retry(dataSourceCode), guarded);
        return guarded.get();
    }
}
