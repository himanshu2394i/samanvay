package com.samanvay.connector.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.samanvay.audit.api.AuditService;
import com.samanvay.catalog.api.ConnectorCatalog;
import com.samanvay.catalog.api.ConnectorDefinition;
import com.samanvay.catalog.api.ConnectorStatus;
import com.samanvay.catalog.api.DataSourceDefinition;
import com.samanvay.catalog.api.SchemaCatalog;
import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.AdapterResponse;
import com.samanvay.connector.api.Capability;
import com.samanvay.connector.api.ConnectorResult;
import com.samanvay.connector.api.DepartmentChaos;
import com.samanvay.connector.api.ExecutionInputs;
import com.samanvay.connector.api.ProtocolAdapter;
import com.samanvay.connector.internal.mapping.MappingExecutor;
import com.samanvay.consent.api.AccessGrant;
import com.samanvay.consent.api.AccessGrantVerifier;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.OpsMetrics;
import com.samanvay.shared.SubjectRef;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The connector runtime times every adapter exchange per data source and counts its outcome, without
 * changing what the caller sees: results and exceptions are exactly those of the un-instrumented path.
 */
class ConnectorExchangeMetricsTest {

    private static final String SOURCE = "metrics-src";
    private static final String CONNECTOR = "metrics-conn@1";

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final DepartmentChaos chaos = mock(DepartmentChaos.class);

    private ConnectorRuntimeImpl runtime(Supplier<AdapterResponse> adapterBehaviour) {
        ConnectorCatalog catalog = mock(ConnectorCatalog.class);
        ConnectorDefinition connector = new ConnectorDefinition(
                CONNECTOR, "metrics-conn", 1, SOURCE, DataCategory.of("MARKS"),
                "{\"FETCH\":{\"endpoint\":\"/x\"}}", "[]", 1000, ConnectorStatus.PUBLISHED);
        when(catalog.byRef(CONNECTOR)).thenReturn(connector);
        when(catalog.dataSourceFor(connector))
                .thenReturn(new DataSourceDefinition(SOURCE, "EDU", "REST", "host.invalid", "NONE", null, null, null));
        ProtocolAdapter adapter = new ProtocolAdapter() {
            @Override
            public String protocol() {
                return "REST";
            }

            @Override
            public AdapterResponse execute(AdapterRequest request) {
                return adapterBehaviour.get();
            }
        };
        return new ConnectorRuntimeImpl(
                mock(AccessGrantVerifier.class), catalog, mock(SchemaCatalog.class), List.of(adapter),
                new ResilienceRegistries(1, Duration.ofMillis(1)), new MappingExecutor(), mock(AuditService.class),
                chaos, Duration.ofSeconds(5), code -> java.util.Optional.empty(), meters);
    }

    private static AccessGrant grant() {
        return new AccessGrant(UUID.randomUUID(), new byte[0], UUID.randomUUID(), 1, new SubjectRef(UUID.randomUUID()),
                null, DataCategory.of("MARKS"), "EDU", CONNECTOR, null, null, Instant.now(),
                Instant.now().plusSeconds(60), new byte[0]);
    }

    private static ExecutionInputs inputs() {
        return new ExecutionInputs(DataCategory.of("MARKS"), "wf", Map.of(), Map.of(), Map.of());
    }

    private double calls(String outcome) {
        var c = meters.find(OpsMetrics.CONNECTOR_CALLS).tags("source", SOURCE, "outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    private long timed(String outcome) {
        var t = meters.find(OpsMetrics.CONNECTOR_EXCHANGE).tags("source", SOURCE, "outcome", outcome).timer();
        return t == null ? 0 : t.count();
    }

    @Test
    void successful_exchange_is_timed_and_counted_per_data_source() {
        var runtime = runtime(() -> new AdapterResponse(JsonMapper.builder().build().readTree("{\"accountRef\":\"X\"}"), 2));

        assertThat(runtime.execute(grant(), Capability.FETCH, inputs())).isInstanceOf(ConnectorResult.Success.class);

        assertThat(calls("success")).isEqualTo(1);
        assertThat(timed("success")).isEqualTo(1);
        assertThat(calls("failure")).isZero();
        assertThat(calls("unavailable")).isZero();
        // percentiles are published for the timer (p50, p95)
        var snapshot = meters.find(OpsMetrics.CONNECTOR_EXCHANGE).timer().takeSnapshot();
        assertThat(snapshot.percentileValues()).extracting(v -> v.percentile()).containsExactly(0.5, 0.95);
    }

    @Test
    void adapter_exception_is_counted_as_failure_and_still_propagates() {
        var boom = new IllegalStateException("source down");
        var runtime = runtime(() -> {
            throw boom;
        });

        assertThatThrownBy(() -> runtime.execute(grant(), Capability.FETCH, inputs())).isSameAs(boom);

        assertThat(calls("failure")).isEqualTo(1);
        assertThat(timed("failure")).isEqualTo(1);
        assertThat(calls("success")).isZero();
    }

    @Test
    void bank_check_answer_and_missing_source_are_counted_per_source() {
        var runtime = runtime(() -> {
            throw new AssertionError("bank check does not use the protocol adapters");
        });

        // no bank-check adapter is registered for the code: counted as unavailable, the caller still gets the fault
        var outcome = runtime.bankCheck(grant(), "ifsc-bank", new com.samanvay.connector.api.BankCheckAdapter.BankCheckRequest(
                "SBIN0000300", "00001000000001", "Asha Patil"));

        assertThat(outcome).isInstanceOf(com.samanvay.connector.api.SourceOutcome.SourceFault.class);
        assertThat(meters.get(OpsMetrics.CONNECTOR_CALLS).tags("source", "ifsc-bank", "outcome", "unavailable").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void a_killed_source_is_counted_as_unavailable_without_a_latency_sample() {
        when(chaos.killed(SOURCE)).thenReturn(true);
        var runtime = runtime(() -> {
            throw new AssertionError("a killed source must not be called");
        });

        assertThat(runtime.execute(grant(), Capability.FETCH, inputs())).isInstanceOf(ConnectorResult.Unavailable.class);

        assertThat(calls("unavailable")).isEqualTo(1);
        assertThat(timed("unavailable")).isZero();
    }
}
