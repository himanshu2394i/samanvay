package com.samanvay.connector.internal.service;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
class ResilienceRegistries {

    private final CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
    private final BulkheadRegistry bulkheads = BulkheadRegistry.ofDefaults();
    private final RetryRegistry retries = RetryRegistry.ofDefaults();

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
