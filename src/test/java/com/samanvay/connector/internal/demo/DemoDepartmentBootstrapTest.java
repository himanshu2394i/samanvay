package com.samanvay.connector.internal.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.samanvay.catalog.api.CatalogOnboarding;
import com.samanvay.catalog.api.ConnectorCatalog;
import com.samanvay.catalog.api.ConnectorDefinition;
import com.samanvay.catalog.api.ConnectorStatus;
import com.samanvay.catalog.api.ConnectorTestReport;
import com.samanvay.catalog.api.JourneyWrite;
import com.samanvay.catalog.api.ManifestOnboarding;
import com.samanvay.catalog.api.OnboardRequest;
import com.samanvay.catalog.api.OnboardingPlan;
import com.samanvay.catalog.api.OnboardingPlan.DocumentPlan;
import com.samanvay.catalog.api.OnboardingResult;
import com.samanvay.connector.api.ConnectorResult;
import com.samanvay.connector.api.ConnectorRuntime;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.InvalidRequestException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Demo/dev only: on startup the four departments are onboarded from their manifests and each connector whose TRIAL fetch really
 * works is published, so the demo journeys run against the department services. Nothing here may crash startup, and nothing
 * that does not demonstrably work is published.
 */
class DemoDepartmentBootstrapTest {

    static final String URL = "http://localhost:8092";

    ManifestOnboarding onboarding = mock(ManifestOnboarding.class);
    CatalogOnboarding wizard = mock(CatalogOnboarding.class);
    JourneyWrite journeys = mock(JourneyWrite.class);
    ConnectorCatalog catalog = mock(ConnectorCatalog.class);
    ConnectorRuntime runtime = mock(ConnectorRuntime.class);

    DemoDepartmentBootstrap bootstrap() {
        return new DemoDepartmentBootstrap(onboarding, wizard, journeys, catalog, runtime, List.of(URL), 3, Duration.ZERO);
    }

    static DocumentPlan doc(String category, boolean ready) {
        return new DocumentPlan(category, category, "REST", "dbt-rest", "dbt-bank", true, "Credential/BankAccount@1", List.of(), List.of(), List.of(), ready);
    }

    static OnboardingPlan plan(boolean exists, boolean onboardedBefore, boolean changed, DocumentPlan... docs) {
        return new OnboardingPlan("DBT", "Direct Benefit Transfer", "d".repeat(64), exists, onboardedBefore, changed, List.of(docs), List.of(), List.of());
    }

    ConnectorDefinition connector(String ref, String sample) {
        String caps = "{\"FETCH\":{\"endpoint\":\"/v1/bank\"" + (sample == null ? "" : ",\"sample_person_id\":\"" + sample + "\"") + "}}";
        return new ConnectorDefinition(ref, "dbt-bank", 2, "dbt-rest", DataCategory.of("BANK_ACCOUNT"), caps, "[]", 1000, ConnectorStatus.DRAFT);
    }

    OnboardingResult result(List<String> connectors, List<String> journeysCreated) {
        return new OnboardingResult("DBT", List.of("dbt-rest"), connectors, List.of("map-x"), journeysCreated, List.of(), List.of());
    }

    @BeforeEach
    void defaults() {
        when(wizard.test(anyString())).thenReturn(new ConnectorTestReport(true, List.of()));
    }

    @Test
    void a_department_that_is_not_running_is_reported_unreachable_after_the_retries_and_nothing_is_thrown() {
        when(onboarding.plan(URL)).thenThrow(new InvalidRequestException("Could not read a Samanvay manifest"));
        var out = bootstrap().run();
        assertThat(out).singleElement().satisfies(o -> assertThat(o.status()).isEqualTo(DemoDepartmentBootstrap.Status.UNREACHABLE));
        verify(onboarding, org.mockito.Mockito.times(3)).plan(URL);
        verify(onboarding, never()).onboard(any());
    }

    @Test
    void a_department_already_onboarded_from_its_manifest_and_unchanged_is_left_alone() {
        when(onboarding.plan(URL)).thenReturn(plan(true, true, false, doc("BANK_ACCOUNT", true)));
        assertThat(bootstrap().run()).singleElement().satisfies(o -> assertThat(o.status()).isEqualTo(DemoDepartmentBootstrap.Status.UP_TO_DATE));
        verify(onboarding, never()).onboard(any());
    }

    @Test
    void a_seeded_department_never_onboarded_from_a_manifest_is_onboarded_even_though_it_exists() {
        when(onboarding.plan(URL)).thenReturn(plan(true, false, false, doc("BANK_ACCOUNT", true)));
        when(onboarding.onboard(any())).thenReturn(result(List.of(), List.of()));
        bootstrap().run();
        verify(onboarding).onboard(any());
    }

    @Test
    void a_changed_manifest_is_onboarded_again() {
        when(onboarding.plan(URL)).thenReturn(plan(true, true, true, doc("BANK_ACCOUNT", true)));
        when(onboarding.onboard(any())).thenReturn(result(List.of(), List.of()));
        bootstrap().run();
        verify(onboarding).onboard(any());
    }

    @Test
    void only_ready_documents_are_onboarded_with_the_reviewed_digest_and_the_suggestions_accepted() {
        when(onboarding.plan(URL)).thenReturn(plan(false, false, false, doc("BANK_ACCOUNT", true), doc("WEATHER", false)));
        when(onboarding.onboard(any())).thenReturn(result(List.of(), List.of()));
        bootstrap().run();
        ArgumentCaptor<OnboardRequest> req = ArgumentCaptor.forClass(OnboardRequest.class);
        verify(onboarding).onboard(req.capture());
        assertThat(req.getValue().categories()).containsExactly("BANK_ACCOUNT");
        assertThat(req.getValue().manifestDigest()).isEqualTo("d".repeat(64));
        assertThat(req.getValue().baseUrl()).isEqualTo(URL);
        assertThat(req.getValue().acceptSuggestedMappings()).isTrue();
    }

    @Test
    void a_signing_key_seen_for_the_first_time_is_approved_the_way_the_suggested_mappings_are() {
        when(onboarding.plan(URL)).thenReturn(plan(false, false, false, doc("BANK_ACCOUNT", true)).withManifestKey("KEY-T", null));
        when(onboarding.onboard(any())).thenReturn(result(List.of(), List.of()));
        bootstrap().run();
        ArgumentCaptor<OnboardRequest> req = ArgumentCaptor.forClass(OnboardRequest.class);
        verify(onboarding).onboard(req.capture());
        assertThat(req.getValue().approvedManifestKey()).isEqualTo("KEY-T");
    }

    @Test
    void a_signing_key_that_changed_after_one_was_pinned_is_never_auto_approved() {
        when(onboarding.plan(URL)).thenReturn(plan(true, true, true, doc("BANK_ACCOUNT", true)).withManifestKey("KEY-NEW", "KEY-OLD"));
        var out = bootstrap().run();
        assertThat(out).singleElement().satisfies(o -> {
            assertThat(o.status()).isEqualTo(DemoDepartmentBootstrap.Status.FAILED);
            assertThat(o.message()).contains("key");
        });
        verify(onboarding, never()).onboard(any());
    }

    @Test
    void a_connector_whose_trial_works_is_published() {
        when(onboarding.plan(URL)).thenReturn(plan(false, false, false, doc("BANK_ACCOUNT", true)));
        when(onboarding.onboard(any())).thenReturn(result(List.of("dbt-bank@2"), List.of()));
        when(catalog.byRef("dbt-bank@2")).thenReturn(connector("dbt-bank@2", "DBT-1001"));
        when(runtime.trial(eq("dbt-bank@2"), eq("DBT-1001"), any())).thenReturn(mock(ConnectorResult.Success.class));

        var out = bootstrap().run().get(0);

        verify(wizard).publish(eq("dbt-bank@2"), any());
        assertThat(out.status()).isEqualTo(DemoDepartmentBootstrap.Status.ONBOARDED);
        assertThat(out.published()).containsExactly("dbt-bank@2");
        assertThat(out.leftDraft()).isEmpty();
    }

    @Test
    void a_connector_whose_trial_fails_is_left_as_a_draft_with_the_reason_and_is_not_published() {
        when(onboarding.plan(URL)).thenReturn(plan(false, false, false, doc("BANK_ACCOUNT", true)));
        when(onboarding.onboard(any())).thenReturn(result(List.of("dbt-bank@2"), List.of()));
        when(catalog.byRef("dbt-bank@2")).thenReturn(connector("dbt-bank@2", "DBT-1001"));
        when(runtime.trial(anyString(), anyString(), any())).thenThrow(new IllegalStateException("source 'dbt-rest' is missing credential parameter 'client_secret'"));

        var out = bootstrap().run().get(0);

        verify(wizard, never()).publish(anyString(), any());
        assertThat(out.published()).isEmpty();
        assertThat(out.leftDraft()).singleElement().asString().contains("dbt-bank@2").contains("client_secret");
    }

    @Test
    void a_trial_that_finds_nothing_or_a_failing_config_test_or_no_sample_person_also_leaves_it_a_draft() {
        when(onboarding.plan(URL)).thenReturn(plan(false, false, false, doc("BANK_ACCOUNT", true)));
        when(onboarding.onboard(any())).thenReturn(result(List.of("a@1", "b@1", "c@1"), List.of()));
        when(catalog.byRef("a@1")).thenReturn(connector("a@1", "X"));
        when(runtime.trial(eq("a@1"), anyString(), any())).thenReturn(new ConnectorResult.NotFound("no such person"));
        when(catalog.byRef("b@1")).thenReturn(connector("b@1", "X"));
        when(wizard.test("b@1")).thenReturn(new ConnectorTestReport(false, List.of("missing mapping")));
        when(catalog.byRef("c@1")).thenReturn(connector("c@1", null));

        var out = bootstrap().run().get(0);

        verify(wizard, never()).publish(anyString(), any());
        assertThat(out.leftDraft()).hasSize(3);
    }

    @Test
    void journeys_created_are_published_when_ready_and_a_refusal_does_not_stop_the_others() {
        when(onboarding.plan(URL)).thenReturn(plan(false, false, false, doc("BANK_ACCOUNT", true)));
        when(onboarding.onboard(any())).thenReturn(result(List.of(), List.of("J_OK", "J_NOT_READY")));
        when(journeys.publishJourney("J_NOT_READY")).thenThrow(new InvalidRequestException("Cannot publish J_NOT_READY yet"));

        var out = bootstrap().run().get(0);

        verify(journeys).publishJourney("J_OK");
        verify(journeys).publishJourney("J_NOT_READY");
        assertThat(out.journeysPublished()).containsExactly("J_OK");
        assertThat(out.leftDraft()).anyMatch(s -> s.contains("J_NOT_READY"));
    }

    @Test
    void a_failure_onboarding_one_department_is_reported_and_does_not_stop_the_next() {
        var two = new DemoDepartmentBootstrap(onboarding, wizard, journeys, catalog, runtime, List.of("http://a", "http://b"), 1, Duration.ZERO);
        when(onboarding.plan("http://a")).thenReturn(plan(false, false, false, doc("BANK_ACCOUNT", true)));
        when(onboarding.onboard(any())).thenThrow(new InvalidRequestException("The department's manifest has changed")).thenReturn(result(List.of(), List.of()));
        when(onboarding.plan("http://b")).thenReturn(plan(false, false, false, doc("BANK_ACCOUNT", true)));

        var out = two.run();

        assertThat(out).hasSize(2);
        assertThat(out.get(0).status()).isEqualTo(DemoDepartmentBootstrap.Status.FAILED);
        assertThat(out.get(0).message()).contains("manifest has changed");
        assertThat(out.get(1).status()).isEqualTo(DemoDepartmentBootstrap.Status.ONBOARDED);
    }

    @Test
    void a_department_with_nothing_ready_is_reported_not_onboarded_empty() {
        when(onboarding.plan(URL)).thenReturn(plan(false, false, false, doc("WEATHER", false)));
        var out = bootstrap().run().get(0);
        assertThat(out.status()).isEqualTo(DemoDepartmentBootstrap.Status.FAILED);
        verify(onboarding, never()).onboard(any());
    }

    @Test
    void the_trial_is_attributed_to_the_bootstrap_not_to_a_citizen() {
        when(onboarding.plan(URL)).thenReturn(plan(false, false, false, doc("BANK_ACCOUNT", true)));
        when(onboarding.onboard(any())).thenReturn(result(List.of("dbt-bank@2"), List.of()));
        when(catalog.byRef("dbt-bank@2")).thenReturn(connector("dbt-bank@2", "DBT-1001"));
        when(runtime.trial(anyString(), anyString(), any())).thenReturn(mock(ConnectorResult.Success.class));
        bootstrap().run();
        ArgumentCaptor<com.samanvay.shared.PrincipalRef> by = ArgumentCaptor.forClass(com.samanvay.shared.PrincipalRef.class);
        verify(runtime).trial(anyString(), anyString(), by.capture());
        assertThat(by.getValue().kind()).isEqualTo(com.samanvay.shared.PrincipalRef.Kind.ADMIN);
        assertThat(by.getValue().id()).isEqualTo("demo-bootstrap");
        assertThat(Map.of()).isEmpty();
    }
}
