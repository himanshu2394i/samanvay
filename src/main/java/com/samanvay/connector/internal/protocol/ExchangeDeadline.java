package com.samanvay.connector.internal.protocol;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * One absolute deadline for a whole connector exchange: every retry attempt, each connect, send
 * and full-body read. {@code ConnectorRuntimeImpl} opens it around the resilience-wrapped
 * adapter call (retries included); {@link DeadlineHttp} spends what is left of it. Thread-bound:
 * adapters run on the calling thread (semaphore bulkhead, synchronous retry).
 */
public final class ExchangeDeadline implements AutoCloseable {

    private static final ThreadLocal<Instant> CURRENT = new ThreadLocal<>();

    private final Instant previous;

    private ExchangeDeadline(Instant deadline) {
        this.previous = CURRENT.get();
        CURRENT.set(deadline);
    }

    /** Starts a deadline {@code total} from now; close it when the exchange is over. */
    public static ExchangeDeadline start(Duration total) {
        return new ExchangeDeadline(Instant.now().plus(total));
    }

    /** Time left on the current thread's deadline, if one is open (may be zero or negative). */
    public static Optional<Duration> remaining() {
        Instant deadline = CURRENT.get();
        return deadline == null ? Optional.empty() : Optional.of(Duration.between(Instant.now(), deadline));
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
