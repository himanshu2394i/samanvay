package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.catalog.api.CatalogOnboarding;
import com.samanvay.catalog.api.ConnectorCatalog;
import com.samanvay.catalog.api.ConnectorDraft;
import com.samanvay.catalog.api.DataSourceDraft;
import com.samanvay.catalog.api.DepartmentDraft;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

/** A data source keeps the non-secret half of its auth (the manifest's auth block) next to its auth type. */
@SpringBootTest(classes = SamanvayApplication.class)
class DataSourceAuthSpecIT extends PostgresIntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String SPEC = "{\"scheme\":\"API_KEY\",\"parameters\":[{\"name\":\"X-Api-Key\",\"in\":\"header\",\"secret\":true}]}";

    @Autowired
    CatalogOnboarding onboarding;

    @Autowired
    ConnectorCatalog catalog;

    String dept() {
        String code = "AUTH" + System.nanoTime();
        onboarding.registerDepartment(new DepartmentDraft(code, "Auth Dept", "a", "a@example.gov", 1000));
        return code;
    }

    @Test
    void the_auth_spec_is_stored_and_read_back_for_the_connectors_data_source() {
        String d = dept();
        var def = onboarding.registerDataSource(new DataSourceDraft("as-1-" + d, d, "REST", "dept.example.gov", "API_KEY", "secret:as-1-" + d, SPEC));
        assertThat(def.authSpecJson()).isEqualTo(SPEC);

        var draft = onboarding.createDraft(new ConnectorDraft("as-c-" + d, def.code(), DataCategory.of("FIRE_NOC"), "{\"FETCH\":{\"endpoint\":\"/x\"}}", "[]", 1000));
        var back = catalog.dataSourceFor(catalog.byRef(draft.ref()));
        // JSONB reformats whitespace, so compare as a tree
        assertThat(back.authType()).isEqualTo("API_KEY");
        assertThat(JSON.readTree(back.authSpecJson())).isEqualTo(JSON.readTree(SPEC));
    }

    @Test
    void a_data_source_registered_the_old_way_has_no_spec_and_keeps_its_auth_type() {
        String d = dept();
        var def = onboarding.registerDataSource(new DataSourceDraft("as-2-" + d, d, "REST", "dept.example.gov", "NONE", "secret:none"));
        var draft = onboarding.createDraft(new ConnectorDraft("as-c2-" + d, def.code(), DataCategory.of("FIRE_NOC"), "{\"FETCH\":{\"endpoint\":\"/x\"}}", "[]", 1000));
        var back = catalog.dataSourceFor(catalog.byRef(draft.ref()));
        assertThat(back.authType()).isEqualTo("NONE");
        assertThat(JSON.readTree(back.authSpecJson())).isEqualTo(JSON.readTree("{}"));
    }
}
