package com.samanvay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The citizen side of department login ships with the portals: a shared script, a callback page, and a button in each portal. */
class DepartmentLoginStaticPagesTest {

    static final List<String> PORTALS = List.of("scholarship", "licence", "farmer");

    @Test
    void the_shared_script_carries_the_request_and_the_proof_and_decides_nothing() {
        String js = page("shared/dept-login.js");
        assertThat(js).contains("SamanvayDeptLogin");
        assertThat(js).contains("/api/identity/department-login");
        assertThat(js).contains("/api/identity/links");
        assertThat(js).contains("DEPT_ASSERTION");
        assertThat(js).contains("/shared/dept-callback.html");
        // the login result is only ever carried to the server, which verifies it
        assertThat(js).contains("safeReturnPath").contains("isHttp");
        assertThat(js).doesNotContain("eval(").doesNotContain("innerHTML");
    }

    @Test
    void the_callback_page_finishes_the_link_and_explains_a_failure() {
        String html = page("shared/dept-callback.html");
        assertThat(html).contains("Finishing your department login");
        assertThat(html).contains("/shared/auth.js").contains("/shared/dept-login.js").contains("/shared/dept-callback.js");
        assertThat(html).contains("Back to the portal");
        // no inline script, so a Content-Security-Policy can be added later without breaking the page
        assertThat(html).doesNotContainPattern("<script>").doesNotContain("innerHTML");
        String js = page("shared/dept-callback.js");
        assertThat(js).contains("SamanvayDeptLogin.complete").contains("SamanvayAuth.ready").contains("alert");
        assertThat(js).doesNotContain("innerHTML");
    }

    @Test
    void every_portal_loads_the_script_and_offers_the_login_only_where_the_department_publishes_one() {
        for (String portal : PORTALS) {
            assertThat(page(portal + "/index.html")).as(portal).contains("/shared/dept-login.js");
            String js = page(portal + "/portal.js");
            assertThat(js).as(portal).contains("departmentLoginAvailable");
            assertThat(js).as(portal).contains("data-proof=\"dept-login\"");
            assertThat(js).as(portal).contains("SamanvayDeptLogin.start");
            // the labelled OTP demo stays, but ONLY for a department that publishes no login of its own: a link made with a
            // made-up local id is one that department does not recognise. DigiLocker is gone altogether.
            assertThat(js).as(portal).contains("data-proof=\"otp\"").doesNotContainIgnoringCase("digilocker");
            assertThat(js).as(portal).contains("legacy mock proofs only when the department has no login of its own");
            // coming back from a department login must land on the connect panel, not an empty details form
            assertThat(js).as(portal).contains("SamanvayDeptLogin.takeReturned()");
        }
        assertThat(page("shared/dept-login.js")).contains("takeReturned");
    }

    private static String page(String path) {
        try (InputStream in = DepartmentLoginStaticPagesTest.class.getResourceAsStream("/static/" + path)) {
            assertThat(in).as(path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
