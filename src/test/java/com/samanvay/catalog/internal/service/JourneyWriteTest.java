package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.samanvay.catalog.api.JourneyDraft;
import com.samanvay.catalog.internal.domain.ConnectorEntity;
import com.samanvay.catalog.internal.domain.DataSourceEntity;
import com.samanvay.catalog.internal.domain.JourneyEntity;
import com.samanvay.catalog.internal.repository.ConnectorRepository;
import com.samanvay.catalog.internal.repository.DataSourceRepository;
import com.samanvay.catalog.internal.repository.JourneyRepository;
import com.samanvay.shared.InvalidRequestException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Journeys onboarded from a manifest: create validates and stays DRAFT; publish is gated on real
 * connector coverage (a PUBLISHED connector whose data source belongs to the required department).
 */
class JourneyWriteTest {

    private final JourneyRepository journeys = mock(JourneyRepository.class);
    private final ConnectorRepository connectors = mock(ConnectorRepository.class);
    private final DataSourceRepository dataSources = mock(DataSourceRepository.class);

    private CatalogServices svc() {
        return new CatalogServices(null, dataSources, connectors, null, journeys, null, null, e -> {});
    }

    private JourneyDraft draft(List<String> categories) {
        return new JourneyDraft("SANDBOX_SUBSIDY", "Sandbox subsidy", "SBX", 96, "SANDBOX_ELIGIBILITY", "SANDBOX",
                categories, Map.of("INCOME_CERTIFICATE", "REVENUE"));
    }

    @Test
    void create_requires_a_code() {
        assertThatThrownBy(() -> svc().createJourney(
                new JourneyDraft("  ", "n", "P", 72, "X", "R", List.of("INCOME_CERTIFICATE"), Map.of())))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void create_requires_at_least_one_category() {
        assertThatThrownBy(() -> svc().createJourney(draft(List.of())))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void create_rejects_a_duplicate_code() {
        when(journeys.existsById("SANDBOX_SUBSIDY")).thenReturn(true);
        assertThatThrownBy(() -> svc().createJourney(draft(List.of("INCOME_CERTIFICATE"))))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void create_saves_a_draft() {
        when(journeys.existsById("SANDBOX_SUBSIDY")).thenReturn(false);
        when(journeys.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var def = svc().createJourney(draft(List.of("INCOME_CERTIFICATE")));
        assertThat(def.status()).isEqualTo("DRAFT");
        assertThat(def.requiredCategories()).containsExactly("INCOME_CERTIFICATE");
        assertThat(def.policy().sources()).containsEntry("INCOME_CERTIFICATE", "REVENUE");
    }

    @Test
    void publish_is_blocked_when_a_required_connector_is_missing() {
        when(journeys.findById("SANDBOX_SUBSIDY")).thenReturn(Optional.of(entity()));
        when(connectors.findAll()).thenReturn(List.of()); // nothing published yet
        assertThatThrownBy(() -> svc().publishJourney("SANDBOX_SUBSIDY"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("INCOME_CERTIFICATE (REVENUE)");
    }

    @Test
    void publish_succeeds_when_every_category_is_covered() {
        JourneyEntity e = entity();
        when(journeys.findById("SANDBOX_SUBSIDY")).thenReturn(Optional.of(e));
        when(journeys.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(connectors.findAll()).thenReturn(List.of(connector("INCOME_CERTIFICATE", "rev-src")));
        when(dataSources.findById("rev-src")).thenReturn(Optional.of(dataSource("REVENUE")));

        var def = svc().publishJourney("SANDBOX_SUBSIDY");
        assertThat(def.status()).isEqualTo("PUBLISHED");
    }

    private static JourneyEntity entity() {
        JourneyEntity e = new JourneyEntity();
        e.setCode("SANDBOX_SUBSIDY");
        e.setName("Sandbox subsidy");
        e.setRequiredCategories(new String[] {"INCOME_CERTIFICATE"});
        e.setPolicy("{\"sla_hours\":96,\"sources\":{\"INCOME_CERTIFICATE\":\"REVENUE\"}}");
        e.setStatus("DRAFT");
        return e;
    }

    private static ConnectorEntity connector(String category, String dataSourceCode) {
        ConnectorEntity c = new ConnectorEntity();
        c.setDataCategory(category);
        c.setDataSourceCode(dataSourceCode);
        c.setStatus("PUBLISHED");
        return c;
    }

    private static DataSourceEntity dataSource(String departmentCode) {
        DataSourceEntity d = new DataSourceEntity();
        d.setDepartmentCode(departmentCode);
        return d;
    }
}
