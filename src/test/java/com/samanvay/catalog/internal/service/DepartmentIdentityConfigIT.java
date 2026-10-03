package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.catalog.api.CatalogOnboarding;
import com.samanvay.catalog.api.DepartmentCatalog;
import com.samanvay.catalog.api.DepartmentDraft;
import com.samanvay.catalog.api.DepartmentIdentity;
import com.samanvay.shared.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * How a citizen proves who they are at a department (its login URL, public keys, issuer and person-ID type, from
 * the manifest's identity block) is kept with the department and read back by the identity module.
 */
@SpringBootTest(classes = SamanvayApplication.class)
class DepartmentIdentityConfigIT extends PostgresIntegrationTest {

    @Autowired
    CatalogOnboarding onboarding;

    @Autowired
    DepartmentCatalog departments;

    static final DepartmentIdentity ID = new DepartmentIdentity(
            "REVENUE_PERSON_ID", "https://revenue.example.gov/login", "https://revenue.example.gov/.well-known/jwks.json", "dept:REVENUE");

    @Test
    void a_departments_identity_config_is_stored_and_read_back() {
        String code = "IDC" + System.nanoTime();
        onboarding.registerDepartment(new DepartmentDraft(code, "Id Dept", "idp", "a@example.gov", 1000, ID));
        assertThat(departments.identity(code)).contains(ID);
    }

    @Test
    void a_department_registered_without_identity_has_none() {
        String code = "IDN" + System.nanoTime();
        onboarding.registerDepartment(new DepartmentDraft(code, "No Id Dept", "idp", "a@example.gov", 1000));
        assertThat(departments.identity(code)).isEmpty();
    }

    @Test
    void an_unknown_department_has_no_identity() {
        assertThat(departments.identity("NOPE-" + System.nanoTime())).isEmpty();
    }

    @Test
    void seeded_departments_registered_before_this_feature_have_none() {
        assertThat(departments.identity("SCHOLARSHIP")).isEmpty();
    }
}
