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
import com.samanvay.shared.SubjectRef;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Some departments key each document separately (a certificate number per certificate). Their connector declares a
 * resolve step: ask first "which documents does this person hold?", pick one, then fetch it by that key. Without the
 * step, the document is fetched with the person ID as before.
 */
class ConnectorResolveStepTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String SOURCE = "rev-income-rest";
    static final String CONNECTOR = "rev-income@1";
    static final String INPUTS = "[{\"name\":\"personId\",\"from\":\"link.localIdToken\"}]";
    static final String RESOLVE = "\"resolve\":{\"path\":\"/v1/persons/{personId}/documents?type=INCOME_CERTIFICATE\","
            + "\"list_field\":\"documents\",\"key_field\":\"key\",\"select\":\"latest\",\"latest_field\":\"latest\",\"into\":\"key\"}";
    static final String TWO_CERTS = "{\"personId\":\"RV-1001\",\"documents\":[{\"key\":\"INC-2025-0001\",\"latest\":false},{\"key\":\"INC-2026-0007\",\"latest\":true}]}";

    final List<AdapterRequest> calls = new ArrayList<>();
    final MeterRegistry meters = new SimpleMeterRegistry();

    ConnectorRuntimeImpl runtime(String capabilities, Function<AdapterRequest, String> respond) {
        ConnectorCatalog catalog = mock(ConnectorCatalog.class);
        ConnectorDefinition connector = new ConnectorDefinition(CONNECTOR, "rev-income", 1, SOURCE, DataCategory.of("INCOME_CERTIFICATE"),
                capabilities, INPUTS, 1000, ConnectorStatus.PUBLISHED);
        when(catalog.byRef(CONNECTOR)).thenReturn(connector);
        when(catalog.dataSourceFor(connector)).thenReturn(new DataSourceDefinition(SOURCE, "REVENUE", "REST", "rev.invalid", "NONE", null, null, null));
        ProtocolAdapter adapter = new ProtocolAdapter() {
            @Override
            public String protocol() {
                return "REST";
            }

            @Override
            public AdapterResponse execute(AdapterRequest request) {
                calls.add(request);
                String body = respond.apply(request);
                return new AdapterResponse(JSON.readTree(body), body.length());
            }
        };
        return new ConnectorRuntimeImpl(mock(AccessGrantVerifier.class), catalog, mock(SchemaCatalog.class), List.of(adapter),
                new ResilienceRegistries(1, Duration.ofMillis(1)), new MappingExecutor(), mock(AuditService.class), mock(DepartmentChaos.class),
                Duration.ofSeconds(5), code -> java.util.Optional.empty(), meters);
    }

    static AccessGrant grant() {
        return new AccessGrant(UUID.randomUUID(), new byte[0], UUID.randomUUID(), 1, new SubjectRef(UUID.randomUUID()), null,
                DataCategory.of("INCOME_CERTIFICATE"), "REVENUE", CONNECTOR, null, null, Instant.now(), Instant.now().plusSeconds(60), new byte[0]);
    }

    static ExecutionInputs inputs() {
        return new ExecutionInputs(DataCategory.of("INCOME_CERTIFICATE"), "wf", Map.of("localIdToken", "RV-1001"), Map.of(), Map.of("year", "2026"));
    }

    static String caps(boolean withResolve) {
        return "{\"FETCH\":{\"endpoint\":\"/v1/income/{key}\"" + (withResolve ? "," + RESOLVE : "") + "}}";
    }

    Function<AdapterRequest, String> twoStep(String resolveBody) {
        return r -> r.endpoint().startsWith("/v1/persons/") ? resolveBody : "{\"holderName\":\"Asha Patil\",\"annualIncome\":\"185000\"}";
    }

    @Test
    void the_runtime_resolves_the_latest_key_then_fetches_the_document_with_it() {
        var result = runtime(caps(true), twoStep(TWO_CERTS)).execute(grant(), Capability.FETCH, inputs());

        assertThat(result).isInstanceOf(ConnectorResult.Success.class);
        assertThat(((ConnectorResult.Success) result).canonical().get("holderName").asString()).isEqualTo("Asha Patil");
        assertThat(calls).hasSize(2);
        AdapterRequest resolve = calls.get(0);
        assertThat(resolve.endpoint()).isEqualTo("/v1/persons/{personId}/documents?type=INCOME_CERTIFICATE");
        // only the inputs the resolve path names are sent, not every journey variable
        assertThat(resolve.boundInputs()).containsOnlyKeys("personId").containsEntry("personId", "RV-1001");
        AdapterRequest fetch = calls.get(1);
        assertThat(fetch.endpoint()).isEqualTo("/v1/income/{key}");
        assertThat(fetch.boundInputs()).containsEntry("key", "INC-2026-0007").containsEntry("personId", "RV-1001");
    }

    @Test
    void both_calls_use_the_same_source_protocol_and_auth_details() {
        runtime(caps(true), twoStep(TWO_CERTS)).execute(grant(), Capability.FETCH, inputs());
        assertThat(calls).allSatisfy(c -> {
            assertThat(c.dataSourceCode()).isEqualTo(SOURCE);
            assertThat(c.protocol()).isEqualTo("REST");
            assertThat(c.host()).isEqualTo("rev.invalid");
        });
    }

    @Test
    void select_first_takes_the_first_listed_key() {
        String caps = "{\"FETCH\":{\"endpoint\":\"/v1/income/{key}\"," + RESOLVE.replace("\"latest\"", "\"first\"") + "}}";
        runtime(caps, twoStep(TWO_CERTS)).execute(grant(), Capability.FETCH, inputs());
        assertThat(calls.get(1).boundInputs()).containsEntry("key", "INC-2025-0001");
    }

    @Test
    void latest_with_no_flagged_item_falls_back_to_the_first() {
        runtime(caps(true), twoStep("{\"documents\":[{\"key\":\"A\"},{\"key\":\"B\"}]}")).execute(grant(), Capability.FETCH, inputs());
        assertThat(calls.get(1).boundInputs()).containsEntry("key", "A");
    }

    @Test
    void a_person_with_no_documents_is_not_found_and_the_document_is_never_requested() {
        var result = runtime(caps(true), twoStep("{\"personId\":\"RV-1001\",\"documents\":[]}")).execute(grant(), Capability.FETCH, inputs());
        assertThat(result).isInstanceOf(ConnectorResult.NotFound.class);
        assertThat(calls).hasSize(1);
    }

    @Test
    void a_resolve_answer_without_the_expected_list_is_not_found_not_a_crash() {
        var result = runtime(caps(true), twoStep("{\"unexpected\":true}")).execute(grant(), Capability.FETCH, inputs());
        assertThat(result).isInstanceOf(ConnectorResult.NotFound.class);
        assertThat(calls).hasSize(1);
    }

    @Test
    void without_a_resolve_declaration_there_is_one_call_and_the_person_id_is_the_key() {
        var rt = runtime(caps(false), r -> "{\"holderName\":\"Asha Patil\"}");
        var result = rt.execute(grant(), Capability.FETCH, inputs());
        assertThat(result).isInstanceOf(ConnectorResult.Success.class);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).boundInputs()).containsExactlyInAnyOrderEntriesOf(Map.of("personId", "RV-1001"));
    }

    @Test
    void a_failure_in_the_resolve_call_propagates_like_any_adapter_failure_and_the_document_is_not_requested() {
        var boom = new IllegalStateException("revenue down");
        var rt = runtime(caps(true), r -> {
            throw boom;
        });
        assertThatThrownBy(() -> rt.execute(grant(), Capability.FETCH, inputs())).isSameAs(boom);
        assertThat(calls).hasSize(1);
    }

    @Test
    void the_two_calls_count_as_one_exchange_for_ops_metrics() {
        runtime(caps(true), twoStep(TWO_CERTS)).execute(grant(), Capability.FETCH, inputs());
        double ok = meters.find("samanvay.connector.calls").tags("source", SOURCE, "outcome", "success").counter().count();
        assertThat(ok).isEqualTo(1.0);
    }

    @Test
    void a_key_from_resolve_cannot_overwrite_a_different_declared_input() {
        // `into` names an input the connector already binds from the link: refuse rather than let a department's answer replace it
        String caps = "{\"FETCH\":{\"endpoint\":\"/v1/income/{key}\"," + RESOLVE.replace("\"into\":\"key\"", "\"into\":\"personId\"") + "}}";
        var result = runtime(caps, twoStep(TWO_CERTS));
        assertThatThrownBy(() -> result.execute(grant(), Capability.FETCH, inputs())).isInstanceOf(RuntimeException.class);
    }

    @Test
    void a_post_resolve_sends_the_person_id_in_the_body_and_the_document_call_keeps_its_own_method() {
        String caps = "{\"FETCH\":{\"endpoint\":\"/v1/income/{key}\",\"method\":\"GET\",\"resolve\":{\"method\":\"POST\","
                + "\"path\":\"/v1/documents/search\",\"body_inputs\":\"personId\",\"list_field\":\"documents\",\"key_field\":\"key\","
                + "\"select\":\"first\",\"into\":\"key\"}}}";
        runtime(caps, r -> r.endpoint().startsWith("/v1/documents/search") ? TWO_CERTS : "{\"holderName\":\"Asha Patil\"}")
                .execute(grant(), Capability.FETCH, inputs());

        AdapterRequest resolve = calls.get(0);
        assertThat(resolve.access()).containsEntry("method", "POST").containsEntry("body_inputs", "personId");
        assertThat(resolve.boundInputs()).containsOnlyKeys("personId");
        AdapterRequest fetch = calls.get(1);
        assertThat(fetch.access()).containsEntry("method", "GET").doesNotContainKey("body_inputs");
    }

    @Test
    void a_get_resolve_never_inherits_a_post_document_call() {
        String caps = "{\"FETCH\":{\"endpoint\":\"/v1/income/{key}\",\"method\":\"POST\",\"body_inputs\":\"key\"," + RESOLVE + "}}";
        runtime(caps, twoStep(TWO_CERTS)).execute(grant(), Capability.FETCH, inputs());
        assertThat(calls.get(0).access()).doesNotContainKey("body_inputs").satisfies(a -> assertThat(a.getOrDefault("method", "GET")).isEqualTo("GET"));
        assertThat(calls.get(1).access()).containsEntry("method", "POST").containsEntry("body_inputs", "key");
    }
}
