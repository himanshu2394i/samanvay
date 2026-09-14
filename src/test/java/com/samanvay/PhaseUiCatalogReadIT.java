package com.samanvay;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.catalog.api.ConnectorDefinition;
import com.samanvay.catalog.api.JourneyDefinition;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.tracking.api.ApplicationSummary;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PhaseUiCatalogReadIT extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void catalogListsSeededJourneysAndPublishedConnectors() {
        RestClient http = RestClient.create();
        JourneyDefinition[] journeys = http.get()
                .uri(url("/api/catalog/journeys"))
                .retrieve()
                .body(JourneyDefinition[].class);
        assertThat(journeys).isNotEmpty();
        List<String> codes = Arrays.stream(journeys).map(JourneyDefinition::code).toList();
        assertThat(codes).contains("POST_MATRIC_SCHOLARSHIP", "BUSINESS_NOC", "FARMER_SUBSIDY");

        ConnectorDefinition[] connectors = http.get()
                .uri(url("/api/catalog/connectors"))
                .retrieve()
                .body(ConnectorDefinition[].class);
        assertThat(connectors).isNotEmpty();
        assertThat(Arrays.stream(connectors).map(ConnectorDefinition::dataSourceCode))
                .contains("revenue-rest-mock");

        ApplicationSummary[] apps = http.get()
                .uri(url("/api/applications?size=1"))
                .retrieve()
                .body(ApplicationSummary[].class);
        assertThat(apps).isNotNull();
    }

    @Test
    void demoEntryLeadsWithCallerAndServesOpsCutaway() {
        RestClient http = RestClient.create();
        String root = http.get().uri(url("/")).retrieve().body(String.class);
        assertThat(root).contains("/scholarship/");
        assertThat(root).contains("/licence/");
        assertThat(root).contains("Citizen services");
        assertThat(root).doesNotContain("Command Center");

        String demo = http.get().uri(url("/demo.html")).retrieve().body(String.class);
        assertThat(demo).contains("interoperability");
        assertThat(demo).contains("Start demo (external caller)");
        assertThat(demo).contains("/caller.html");
        assertThat(demo).contains("/scholarship/");
        assertThat(demo).contains("Open Citizen services");
        assertThat(demo).doesNotContain("Command Center");

        String ops = http.get().uri(url("/command.html")).retrieve().body(String.class);
        assertThat(ops).contains("Command");
        assertThat(ops).contains("Tracked applications");
        assertThat(ops).contains("State operations");

        String schemes = http.get().uri(url("/schemes.html")).retrieve().body(String.class);
        assertThat(schemes).contains("Published schemes");
        assertThat(schemes).contains("FARMER_SUBSIDY");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
