package com.samanvay.shared;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/**
 * Names and tags of the Micrometer meters behind the four ops dashboards (HLD section 10). The
 * modules that <em>record</em> (connector, consent, orchestration) and the ops module that
 * <em>reads</em> them share only these strings, so neither side depends on the other.
 *
 * <p>Every tag value is drawn from a bounded set (catalog data-source codes, enum names), so
 * cardinality stays small. Nothing personal (citizen, grant or consent ids) is ever a tag.
 *
 * <p>The meters are read in-process by the staff-only {@code GET /api/ops/metrics}. No actuator,
 * Prometheus or other metrics endpoint is served over HTTP.
 */
public final class OpsMetrics {

    /** Timer: one source exchange (retries included), tags {@code source}, {@code outcome}. */
    public static final String CONNECTOR_EXCHANGE = "samanvay.connector.exchange";

    /** Counter: source calls by {@code source} and {@code outcome} (includes calls that never reached the adapter). */
    public static final String CONNECTOR_CALLS = "samanvay.connector.calls";

    /** Counter: access authorize decisions, tags {@code outcome} (granted/denied) and {@code reason}. */
    public static final String CONSENT_AUTHORIZE = "samanvay.consent.authorize";

    /** Gauge: open rows in the orchestration exception queue. */
    public static final String EXCEPTIONS_OPEN = "samanvay.exceptions.open";

    /** Gauge (seconds): age of the oldest open exception, 0 when the queue is empty. */
    public static final String EXCEPTIONS_OLDEST_AGE = "samanvay.exceptions.oldest.age";

    public static final String TAG_SOURCE = "source";
    public static final String TAG_OUTCOME = "outcome";
    public static final String TAG_REASON = "reason";

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILURE = "failure";
    public static final String OUTCOME_UNAVAILABLE = "unavailable";
    public static final String OUTCOME_GRANTED = "granted";
    public static final String OUTCOME_DENIED = "denied";
    /** {@code reason} tag value of a granted decision. */
    public static final String REASON_NONE = "none";

    public static final double[] LATENCY_PERCENTILES = {0.5, 0.95};

    /** Percentiles are computed over a rolling window of about this long, then age out. */
    public static final Duration LATENCY_WINDOW = Duration.ofMinutes(10);

    /** Records one source exchange (timer) and its outcome (counter). */
    public static void recordConnectorExchange(MeterRegistry meters, String source, String outcome, long nanos) {
        exchangeTimer(meters, source, outcome).record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        countConnectorCall(meters, source, outcome);
    }

    /** Counts a source call that never reached the adapter (chaos kill, no adapter): no latency to record. */
    public static void countConnectorCall(MeterRegistry meters, String source, String outcome) {
        meters.counter(CONNECTOR_CALLS, TAG_SOURCE, source, TAG_OUTCOME, outcome).increment();
    }

    private static Timer exchangeTimer(MeterRegistry meters, String source, String outcome) {
        return Timer.builder(CONNECTOR_EXCHANGE)
                .description("Data-source exchange latency, retries included")
                .tag(TAG_SOURCE, source)
                .tag(TAG_OUTCOME, outcome)
                .publishPercentiles(LATENCY_PERCENTILES)
                .distributionStatisticExpiry(LATENCY_WINDOW)
                .register(meters);
    }

    private OpsMetrics() {}
}
