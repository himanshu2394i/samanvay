package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.catalog.api.ImportPreview;
import com.samanvay.catalog.api.MappingCatalog;
import com.samanvay.catalog.api.SpecImport;
import com.samanvay.shared.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = SamanvayApplication.class)
class SpecImportIT extends PostgresIntegrationTest {

    @Autowired
    SpecImport importer;

    @Autowired
    MappingCatalog mappings;

    @Test
    void previewDoesNotPersistMapping() {
        String spec =
                """
                {"openapi":"3.0.0","paths":{"/income":{"get":{"operationId":"getIncome","responses":{"200":{"content":{"application/json":{"schema":{"type":"object","properties":{"annual_income":{"type":"number"},"holder_name":{"type":"string"}}}}}}}}}}}
                """;
        ImportPreview preview = importer.preview(spec, "getIncome", "Credential/IncomeCertificate@1");
        assertThat(preview.sourceFields()).contains("annual_income");
        assertThat(preview.suggestions()).isNotEmpty();
        assertThat(preview.suggestions()).noneMatch(s -> s.approved());
        org.junit.jupiter.api.Assertions.assertThrows(
                Exception.class, () -> mappings.byRef("map-from-import-must-not-exist"));
    }
}
