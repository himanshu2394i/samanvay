package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.catalog.api.Capability;
import com.samanvay.catalog.api.ConnectorCatalog;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = SamanvayApplication.class)
class ConnectorCatalogIT extends PostgresIntegrationTest {

    @Autowired
    ConnectorCatalog connectors;

    @Test
    void resolveReturnsPublishedScholarshipConnectors() {
        var income = connectors.resolve("REVENUE", DataCategory.INCOME_CERTIFICATE, Capability.FETCH);
        assertThat(income).isPresent();
        assertThat(income.get().ref()).isEqualTo("rev-income@1");
        assertThat(connectors.byRef("rev-income@1").version()).isEqualTo(1);
    }
}
