package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * After V199, the five journey categories that now have a real independent department service resolve
 * to real-transport data sources ({@code base_host != mock.samanvay.test}); the sibling categories
 * with no real service yet stay on the mock backend.
 */
@SpringBootTest(classes = SamanvayApplication.class)
class JourneysRealResolutionTest extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void the_five_repointed_connectors_resolve_real() {
        List<String> real = jdbc.queryForList(
                "SELECT c.ref FROM catalog_connector c JOIN catalog_data_source ds ON ds.code = c.data_source_code "
                        + "WHERE ds.base_host <> 'mock.samanvay.test' AND c.ref IN "
                        + "('rev-income@1','edu-marks@1','dbt-bank@1','muni-property@1','pcb-clearance@1')",
                String.class);
        assertThat(real).containsExactlyInAnyOrder(
                "rev-income@1", "edu-marks@1", "dbt-bank@1", "muni-property@1", "pcb-clearance@1");
    }

    @Test
    void the_categories_without_a_real_service_stay_mock() {
        List<String> mock = jdbc.queryForList(
                "SELECT c.ref FROM catalog_connector c JOIN catalog_data_source ds ON ds.code = c.data_source_code "
                        + "WHERE ds.base_host = 'mock.samanvay.test' AND c.ref IN "
                        + "('rev-caste@1','rev-land@1','fire-noc@1','rev-712@1','agri-crop@1')",
                String.class);
        assertThat(mock).containsExactlyInAnyOrder("rev-caste@1", "rev-land@1", "fire-noc@1", "rev-712@1", "agri-crop@1");
    }
}
