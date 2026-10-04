package com.samanvay.ops.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.samanvay.catalog.api.Capability;
import com.samanvay.catalog.api.CatalogDiscovery;
import com.samanvay.catalog.api.ConnectorCatalog;
import com.samanvay.catalog.api.ConnectorDefinition;
import com.samanvay.catalog.api.ConnectorStatus;
import com.samanvay.catalog.api.DataSourceDefinition;
import com.samanvay.catalog.api.DataSourceHealth;
import com.samanvay.catalog.api.JourneyCatalog;
import com.samanvay.catalog.api.JourneyDefinition;
import com.samanvay.catalog.api.JourneyNotFoundException;
import com.samanvay.catalog.api.JourneyPolicy;
import com.samanvay.connector.api.TrialHistory;
import com.samanvay.ops.internal.service.JourneyStatusView.Category;
import com.samanvay.orchestration.api.JourneyActivity;
import com.samanvay.shared.DataCategory;
import com.samanvay.tracking.api.ApplicationTracking;
import com.samanvay.tracking.api.ApplicationView;
import com.samanvay.tracking.api.StepView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** One journey's connected-and-working view, built from fakes of the catalog, connector, orchestration and tracking APIs. */
class JourneyStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final String CODE = "J1";

    private final JourneyCatalog journeys = mock(JourneyCatalog.class);
    private final ConnectorCatalog connectors = mock(ConnectorCatalog.class);
    private final CatalogDiscovery discovery = mock(CatalogDiscovery.class);
    private final TrialHistory trials = mock(TrialHistory.class);
    private final JourneyActivity activity = mock(JourneyActivity.class);
    private final ApplicationTracking tracking = mock(ApplicationTracking.class);
    private final JourneyStatusService service = new JourneyStatusService(
            journeys, connectors, discovery, trials, activity, tracking, Clock.fixed(NOW, ZoneOffset.UTC));

    private void journey(String... categories) {
        Map<String, String> sources = new java.util.LinkedHashMap<>();
        for (String c : categories) {
            sources.put(c, "DEPT_" + c);
        }
        when(journeys.byCode(CODE)).thenReturn(new JourneyDefinition(
                CODE, "Test scheme", "bpmn", List.of(categories), new JourneyPolicy(false, 48, "REQ", "PURPOSE", "TS", sources), "PUBLISHED"));
        when(journeys.portalUrl(CODE)).thenReturn(Optional.of("https://portal.example/apply"));
        when(activity.activity(eq(CODE), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new JourneyActivity.Activity(0, 0, 0, 0, List.of()));
    }

    private ConnectorDefinition connector(String category, int version) {
        return connector(category, version, "REST");
    }

    private ConnectorDefinition connector(String category, int version, String protocol) {
        ConnectorDefinition c = new ConnectorDefinition(
                "conn-" + category + "@" + version, "conn-" + category, version, "ds-" + category,
                DataCategory.of(category), "{\"FETCH\":{}}", "[]", 1000, ConnectorStatus.PUBLISHED);
        when(connectors.resolve("DEPT_" + category, DataCategory.of(category), Capability.FETCH)).thenReturn(Optional.of(c));
        when(connectors.dataSourceFor(c)).thenReturn(new DataSourceDefinition(
                "ds-" + category, "DEPT_" + category, protocol, "host", "NONE", "secret:none", "{}", "{}"));
        return c;
    }

    private void health(String category, String status) {
        List<DataSourceHealth> all = new ArrayList<>(discovery.listDataSources() == null ? List.of() : discovery.listDataSources());
        all.add(new DataSourceHealth("ds-" + category, "DEPT_" + category, "REST", "host", status, null));
        when(discovery.listDataSources()).thenReturn(all);
    }

    @Test
    void unknownJourneyIsNotFound() {
        when(journeys.byCode("NOPE")).thenThrow(new JourneyNotFoundException("NOPE"));

        assertThatThrownBy(() -> service.status("NOPE")).isInstanceOf(JourneyNotFoundException.class);
    }

    @Test
    void aCategoryIsWorkingOnlyWithAPublishedConnectorWhoseSourceIsNotRed() {
        journey("INCOME", "MARKS", "DOMICILE");
        connector("INCOME", 3);
        health("INCOME", "GREEN");
        connector("MARKS", 1);
        health("MARKS", "RED");
        // DOMICILE has no published connector at all
        when(trials.last("conn-INCOME@3")).thenReturn(Optional.of(new TrialHistory.Trial(NOW.minusSeconds(60), "SUCCESS")));

        JourneyStatusView view = service.status(CODE);

        assertThat(view.code()).isEqualTo(CODE);
        assertThat(view.name()).isEqualTo("Test scheme");
        assertThat(view.requester()).isEqualTo("REQ");
        assertThat(view.status()).isEqualTo("PUBLISHED");
        assertThat(view.portalUrl()).isEqualTo("https://portal.example/apply");
        Map<String, Category> byCategory = new java.util.HashMap<>();
        view.categories().forEach(c -> byCategory.put(c.category(), c));

        Category income = byCategory.get("INCOME");
        assertThat(income.connectorRef()).isEqualTo("conn-INCOME@3");
        assertThat(income.connectorStatus()).isEqualTo("PUBLISHED");
        assertThat(income.department()).isEqualTo("DEPT_INCOME");
        assertThat(income.dataSourceCode()).isEqualTo("ds-INCOME");
        assertThat(income.sourceHealth()).isEqualTo("GREEN");
        assertThat(income.lastTrial()).isEqualTo(new JourneyStatusView.Trial(NOW.minusSeconds(60), "SUCCESS"));
        assertThat(income.working()).isTrue();

        Category marks = byCategory.get("MARKS");
        assertThat(marks.sourceHealth()).isEqualTo("RED");
        assertThat(marks.lastTrial()).isNull();
        assertThat(marks.working()).isFalse();

        Category domicile = byCategory.get("DOMICILE");
        assertThat(domicile.connectorRef()).isNull();
        assertThat(domicile.connectorStatus()).isEqualTo("NONE");
        assertThat(domicile.dataSourceCode()).isNull();
        assertThat(domicile.sourceHealth()).isEqualTo("UNKNOWN");
        assertThat(domicile.working()).isFalse();
        assertThat(view.categories()).extracting(Category::category).containsExactly("INCOME", "MARKS", "DOMICILE");
    }

    @Test
    void anUnprobedRestSourceIsUnknownAndNotWorking() {
        journey("INCOME");
        connector("INCOME", 1); // not in the health list

        Category income = service.status(CODE).categories().getFirst();

        assertThat(income.sourceHealth()).isEqualTo("UNKNOWN");
        assertThat(income.working()).isFalse();
    }

    @Test
    void anSftpOrJdbcSourceIsAlwaysUnknownSoItWorksOnlyWhenItsLastDurableTrialSucceeded() {
        journey("PARCEL", "POLLUTION", "NOTRIAL");
        connector("PARCEL", 1, "SFTP_CSV");
        health("PARCEL", "UNKNOWN");
        connector("POLLUTION", 1, "JDBC");
        health("POLLUTION", "UNKNOWN");
        connector("NOTRIAL", 1, "SFTP_CSV");
        health("NOTRIAL", "UNKNOWN");
        when(trials.last("conn-PARCEL@1")).thenReturn(Optional.of(new TrialHistory.Trial(NOW.minusSeconds(60), "SUCCESS")));
        when(trials.last("conn-POLLUTION@1")).thenReturn(Optional.of(new TrialHistory.Trial(NOW.minusSeconds(60), "NOT_FOUND")));

        Map<String, Category> byCategory = new java.util.HashMap<>();
        service.status(CODE).categories().forEach(c -> byCategory.put(c.category(), c));

        assertThat(byCategory.get("PARCEL").sourceHealth()).isEqualTo("UNKNOWN");
        assertThat(byCategory.get("PARCEL").working()).isTrue();
        assertThat(byCategory.get("POLLUTION").working()).isFalse();
        assertThat(byCategory.get("NOTRIAL").working()).isFalse();
    }

    @Test
    void countsRecentApplicationsAndTheLogComeFromActivityAndTrackingNewestFirst() {
        journey("INCOME");
        connector("INCOME", 3);
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        when(activity.activity(eq(CODE), eq(NOW.minus(Duration.ofDays(7))), eq(20))).thenReturn(new JourneyActivity.Activity(
                2, 5, 1, 4,
                List.of(
                        new JourneyActivity.Recent(newer, "VERIFIED", NOW.minusSeconds(100), Map.of("conn-INCOME", 2)),
                        new JourneyActivity.Recent(older, "REJECTED", NOW.minusSeconds(900), Map.of()))));
        stepsFor(newer, "REF-NEW", new StepView("INCOME", "DEPT_INCOME", "COMPLETED",
                NOW.minusSeconds(100), NOW.minusSeconds(100).plusMillis(250), null, "API", Optional.of("COMPLETED"), Optional.empty()));
        stepsFor(older, "REF-OLD", new StepView("INCOME", "DEPT_INCOME", "FAILED",
                NOW.minusSeconds(900), NOW.minusSeconds(899), null, "API", Optional.of("NOT_FOUND"), Optional.empty()));

        JourneyStatusView view = service.status(CODE);

        assertThat(view.counts()).isEqualTo(new JourneyStatusView.Counts(2, 5, 1, 4));
        assertThat(view.recent()).extracting(JourneyStatusView.Recent::referenceNo).containsExactly("REF-NEW", "REF-OLD");
        assertThat(view.recent().getFirst().state()).isEqualTo("VERIFIED");
        assertThat(view.recent().getFirst().instanceId()).isEqualTo(newer);
        assertThat(view.log()).hasSize(2);
        JourneyStatusView.LogRow ok = view.log().getFirst();
        assertThat(ok.referenceNo()).isEqualTo("REF-NEW");
        assertThat(ok.category()).isEqualTo("INCOME");
        assertThat(ok.department()).isEqualTo("DEPT_INCOME");
        assertThat(ok.connector()).isEqualTo("conn-INCOME@2"); // the version the instance pinned, not today's
        assertThat(ok.outcome()).isEqualTo("COMPLETED");
        assertThat(ok.latencyMs()).isEqualTo(250L);
        assertThat(ok.error()).isNull();
        JourneyStatusView.LogRow failed = view.log().get(1);
        assertThat(failed.connector()).isEqualTo("conn-INCOME@3"); // nothing pinned: today's serving connector
        assertThat(failed.outcome()).isEqualTo("FAILED");
        assertThat(failed.error()).isEqualTo("NOT_FOUND");
        assertThat(failed.latencyMs()).isEqualTo(1000L);
    }

    @Test
    void theLogIsCappedAtOneHundredRowsNewestFirst() {
        journey("INCOME");
        connector("INCOME", 1);
        UUID id = UUID.randomUUID();
        when(activity.activity(eq(CODE), any(), eq(20))).thenReturn(new JourneyActivity.Activity(
                1, 0, 0, 1, List.of(new JourneyActivity.Recent(id, "VERIFIED", NOW, Map.of()))));
        List<StepView> many = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            many.add(new StepView("S" + i, "DEPT_INCOME", "COMPLETED", NOW.minusSeconds(1000 - i), NOW.minusSeconds(1000 - i),
                    null, "API", Optional.of("COMPLETED"), Optional.empty()));
        }
        stepsFor(id, "REF-1", many.toArray(StepView[]::new));

        List<JourneyStatusView.LogRow> log = service.status(CODE).log();

        assertThat(log).hasSize(100);
        assertThat(log.getFirst().category()).isEqualTo("S149");
        assertThat(log).extracting(JourneyStatusView.LogRow::at).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void aRecentInstanceTrackingDoesNotKnowYetIsListedWithoutAReferenceOrLogRows() {
        journey("INCOME");
        connector("INCOME", 1);
        UUID id = UUID.randomUUID();
        when(activity.activity(eq(CODE), any(), eq(20))).thenReturn(new JourneyActivity.Activity(
                1, 0, 0, 1, List.of(new JourneyActivity.Recent(id, "SUBMITTED", NOW, Map.of()))));
        when(tracking.byInstanceId(id)).thenReturn(Optional.empty());

        JourneyStatusView view = service.status(CODE);

        assertThat(view.recent()).singleElement().satisfies(r -> assertThat(r.referenceNo()).isNull());
        assertThat(view.log()).isEmpty();
    }

    private void stepsFor(UUID instanceId, String ref, StepView... steps) {
        when(tracking.byInstanceId(instanceId)).thenReturn(Optional.of(
                new ApplicationView(ref, UUID.randomUUID(), CODE, "SUBMITTED", NOW, null, instanceId)));
        when(tracking.steps(ref)).thenReturn(List.of(steps));
    }
}
