package com.samanvay.connector.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.catalog.api.ConnectorCatalog;
import com.samanvay.catalog.api.ConnectorDefinition;
import com.samanvay.catalog.api.ConnectorStatus;
import com.samanvay.catalog.api.DataSourceDefinition;
import com.samanvay.catalog.api.SchemaCatalog;
import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.AdapterResponse;
import com.samanvay.connector.api.ConnectorResult;
import com.samanvay.connector.api.DepartmentChaos;
import com.samanvay.connector.api.ProtocolAdapter;
import com.samanvay.connector.internal.mapping.MappingExecutor;
import com.samanvay.consent.api.AccessGrantVerifier;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.InvalidRequestException;
import com.samanvay.shared.PrincipalRef;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * A trial fetch runs a drafted connector against the department's published FAKE sample person, so an admin sees real data
 * arrive before publishing. It needs no consent grant (no citizen is involved), records no citizen data access, and leaves
 * exactly one audit row saying who ran which trial.
 */
class ConnectorTrialTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String CONNECTOR = "dbt-bank@2";
    static final PrincipalRef ADMIN = new PrincipalRef(PrincipalRef.Kind.ADMIN, "admin-1");

    final List<AdapterRequest> calls = new ArrayList<>();
    final AuditService audit = mock(AuditService.class);
    final AccessGrantVerifier verifier = mock(AccessGrantVerifier.class);

    ConnectorRuntimeImpl runtime(String capabilities, Function<AdapterRequest, String> respond) {
        ConnectorCatalog catalog = mock(ConnectorCatalog.class);
        ConnectorDefinition connector = new ConnectorDefinition(CONNECTOR, "dbt-bank", 2, "dbt-rest", DataCategory.of("BANK_ACCOUNT"), capabilities,
                "[{\"name\":\"dbtId\",\"from\":\"link.personId\",\"required\":true}]", 1000, ConnectorStatus.DRAFT);
        when(catalog.byRef(CONNECTOR)).thenReturn(connector);
        when(catalog.dataSourceFor(connector)).thenReturn(new DataSourceDefinition("dbt-rest", "DBT", "REST", "dbt.invalid", "NONE", null, null, null));
        ProtocolAdapter adapter = new ProtocolAdapter() {
            public String protocol() {
                return "REST";
            }

            public AdapterResponse execute(AdapterRequest request) {
                calls.add(request);
                String body = respond.apply(request);
                return new AdapterResponse(JSON.readTree(body), body.length());
            }
        };
        return new ConnectorRuntimeImpl(verifier, catalog, mock(SchemaCatalog.class), List.of(adapter), new ResilienceRegistries(1, Duration.ofMillis(1)),
                new MappingExecutor(), audit, mock(DepartmentChaos.class), Duration.ofSeconds(5), code -> java.util.Optional.empty(), new SimpleMeterRegistry());
    }

    static final String CAPS = "{\"FETCH\":{\"endpoint\":\"/v1/bank\"}}";

    @Test
    void a_trial_fetches_the_sample_person_through_the_connector_without_any_consent_grant() {
        var rt = runtime(CAPS, r -> "{\"accountRef\":\"XXXXXX1234\"}");
        var result = rt.trial(CONNECTOR, "DBT-1001", ADMIN);

        assertThat(result).isInstanceOf(ConnectorResult.Success.class);
        assertThat(((ConnectorResult.Success) result).canonical().get("accountRef").asString()).isEqualTo("XXXXXX1234");
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).boundInputs()).containsEntry("dbtId", "DBT-1001");
        verify(verifier, never()).verifyOrThrow(any(), any(), any());
    }

    @Test
    void a_trial_records_one_trial_audit_row_naming_the_admin_and_no_citizen_data_access() {
        runtime(CAPS, r -> "{\"accountRef\":\"X\"}").trial(CONNECTOR, "DBT-1001", ADMIN);

        ArgumentCaptor<AuditEntry> rows = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit, times(1)).record(rows.capture());
        AuditEntry row = rows.getValue();
        assertThat(row.action()).isEqualTo("CONNECTOR_TRIAL");
        assertThat(row.actorId()).isEqualTo("admin-1");
        assertThat(row.resource()).isEqualTo(CONNECTOR);
        assertThat(row.departmentId()).isEqualTo("DBT");
        assertThat(rows.getAllValues()).noneMatch(r -> "DATA_ACCESSED".equals(r.action()));
    }

    @Test
    void the_optional_resolve_step_runs_inside_a_trial_too() {
        String caps = "{\"FETCH\":{\"endpoint\":\"/v1/income/{key}\",\"resolve\":{\"path\":\"/v1/persons/{dbtId}/documents\",\"into\":\"key\"}}}";
        var rt = runtime(caps, r -> r.endpoint().startsWith("/v1/persons/") ? "{\"documents\":[{\"key\":\"K9\",\"latest\":true}]}" : "{\"accountRef\":\"X\"}");
        assertThat(rt.trial(CONNECTOR, "DBT-1001", ADMIN)).isInstanceOf(ConnectorResult.Success.class);
        assertThat(calls).hasSize(2);
        assertThat(calls.get(1).boundInputs()).containsEntry("key", "K9");
    }

    @Test
    void a_person_the_department_does_not_know_is_a_result_not_an_exception() {
        var rt = runtime("{\"FETCH\":{\"endpoint\":\"/v1/income/{key}\",\"resolve\":{\"path\":\"/v1/persons/{dbtId}/documents\",\"into\":\"key\"}}}",
                r -> "{\"documents\":[]}");
        assertThat(rt.trial(CONNECTOR, "NOBODY", ADMIN)).isInstanceOf(ConnectorResult.NotFound.class);
    }

    @Test
    void a_department_failure_propagates_so_the_caller_can_show_the_real_cause_and_the_trial_is_still_audited() {
        var rt = runtime(CAPS, r -> {
            throw new IllegalStateException("department refused");
        });
        assertThatThrownBy(() -> rt.trial(CONNECTOR, "DBT-1001", ADMIN)).isInstanceOf(IllegalStateException.class).hasMessage("department refused");
        verify(audit, times(1)).record(any());
    }

    @Test
    void a_blank_sample_person_is_refused_before_anything_is_called() {
        var rt = runtime(CAPS, r -> "{}");
        for (String bad : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> rt.trial(CONNECTOR, bad, ADMIN)).isInstanceOf(InvalidRequestException.class);
        }
        assertThat(calls).isEmpty();
        verify(audit, never()).record(any());
    }

    // --- who the trial really fetched ---------------------------------------------------------------------------

    static final String CAPS_WITH_SAMPLE = "{\"FETCH\":{\"endpoint\":\"/v1/bank\",\"sample_person_id\":\"DBT-1001\"}}";

    private AuditEntry theAuditRow() {
        ArgumentCaptor<AuditEntry> rows = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit, times(1)).record(rows.capture());
        return rows.getValue();
    }

    @Test
    void the_audit_subject_of_the_departments_own_sample_person_names_it() {
        runtime(CAPS_WITH_SAMPLE, r -> "{\"accountRef\":\"X\"}").trial(CONNECTOR, "DBT-1001", ADMIN);

        assertThat(theAuditRow().subjectId()).isEqualTo("sample:DBT-1001");
    }

    @Test
    void any_other_person_is_audited_by_a_hash_never_by_the_id_itself() {
        // no sample declared, so a named person is allowed (as before) but the trail must not hold the raw id
        runtime(CAPS, r -> "{\"accountRef\":\"X\"}").trial(CONNECTOR, "REAL-CITIZEN-4242", ADMIN);

        AuditEntry row = theAuditRow();
        assertThat(row.subjectId()).startsWith("person:").doesNotContain("REAL-CITIZEN-4242").hasSize("person:".length() + 16);
        assertThat(row.meta().toString()).doesNotContain("REAL-CITIZEN-4242");
    }

    @Test
    void when_the_manifest_declares_a_sample_a_person_of_another_shape_is_refused_before_anything_is_called() {
        var rt = runtime(CAPS_WITH_SAMPLE, r -> "{}");

        assertThatThrownBy(() -> rt.trial(CONNECTOR, "NOBODY", ADMIN)).isInstanceOf(InvalidRequestException.class).hasMessageContaining("sample");
        assertThatThrownBy(() -> rt.trial(CONNECTOR, "4242", ADMIN)).isInstanceOf(InvalidRequestException.class);
        assertThat(calls).isEmpty();
        verify(audit, never()).record(any());
    }

    @Test
    void a_person_that_looks_like_the_sample_is_allowed_and_audited_by_hash() {
        runtime(CAPS_WITH_SAMPLE, r -> "{\"accountRef\":\"X\"}").trial(CONNECTOR, "DBT-9999", ADMIN);

        assertThat(calls).hasSize(1);
        assertThat(theAuditRow().subjectId()).startsWith("person:").doesNotContain("DBT-9999");
    }

    // --- a missing record is not a success ---------------------------------------------------------------------

    @Test
    void a_department_answer_with_no_record_in_it_is_not_found_not_a_success_with_nothing_in_it() {
        // what an SFTP source (no matching row) or a JDBC source (no row) returns: an empty object
        var rt = runtime(CAPS, r -> "{}");
        assertThat(rt.trial(CONNECTOR, "DBT-1001", ADMIN)).isInstanceOf(ConnectorResult.NotFound.class);
    }

    @Test
    void a_record_whose_every_field_is_null_is_not_a_success_either() {
        var rt = runtime(CAPS, r -> "{\"accountRef\":null}");
        assertThat(rt.trial(CONNECTOR, "DBT-1001", ADMIN)).isInstanceOf(ConnectorResult.NotFound.class);
    }
}
