package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.catalog.api.CatalogOnboarding;
import com.samanvay.catalog.api.ConnectorDraft;
import com.samanvay.catalog.api.ConnectorStatus;
import com.samanvay.catalog.api.ConnectorTestReport;
import com.samanvay.catalog.api.DataSourceDraft;
import com.samanvay.catalog.api.DepartmentDraft;
import com.samanvay.catalog.api.FieldMapping;
import com.samanvay.catalog.api.MappingDraft;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = SamanvayApplication.class)
class CatalogOnboardingIT extends PostgresIntegrationTest {

    @Autowired
    CatalogOnboarding onboarding;

    @Test
    void wizardSliceRegistersThroughPublish() {
        String suffix = String.valueOf(System.nanoTime());
        onboarding.registerDepartment(new DepartmentDraft("WIZ" + suffix, "Wizard Dept", "wiz", "w@example.gov", 1000));
        var ds = onboarding.registerDataSource(new DataSourceDraft(
                "wiz-ds-" + suffix, "WIZ" + suffix, "REST", "dept.example.gov", "NONE", "secret:none"));
        var draft = onboarding.createDraft(new ConnectorDraft(
                "wiz-conn-" + suffix,
                ds.code(),
                DataCategory.of("FIRE_NOC"),
                "{\"FETCH\":{\"endpoint\":\"/x\",\"mapping_ref\":\"map-wiz-" + suffix + "@1\"}}",
                "[]",
                1000));
        onboarding.saveMapping(new MappingDraft(
                "map-wiz-" + suffix + "@1", draft.ref(), List.of(new FieldMapping("a", "a", List.of()))));
        var report = onboarding.test(draft.ref());
        assertThat(report.passed()).isTrue();
        assertThat(onboarding.publish(draft.ref(), report).status()).isEqualTo(ConnectorStatus.PUBLISHED);
    }
}
