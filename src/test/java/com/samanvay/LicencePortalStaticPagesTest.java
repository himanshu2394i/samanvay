package com.samanvay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LicencePortalStaticPagesTest {

    @Test
    void licencePortalIsASeparateGovernmentServiceNotScholarshipOrControlPlane() {
        String html = page("licence/index.html");
        String css = page("licence/portal.css");
        String js = page("licence/portal.js");

        assertThat(html).contains("Government of Maharashtra");
        assertThat(html).contains("Business licence");
        assertThat(html).contains("Directorate of Industries");
        assertThat(html).contains("Apply for licence");
        assertThat(html).contains("Connect accounts");
        assertThat(html).contains("Municipal");
        assertThat(html).contains("Fire");
        assertThat(html).contains("Pollution");
        assertThat(html).contains("Revenue");
        assertThat(html).contains("Officer login");
        assertThat(html).contains("demonstration — not SSO");
        assertThat(html).contains("Retry");
        assertThat(html).contains("AUTH STUBBED");
        assertThat(html).contains("not live SSO");
        assertThat(html).contains("Skip to main content");
        assertThat(html).doesNotContain("Control plane");
        assertThat(html).doesNotContain("Apply for scholarship");
        assertThat(html).doesNotContain("POST_MATRIC_SCHOLARSHIP");
        assertThat(html).doesNotContain("Kill");
        assertThat(html).doesNotContain("PARTIALLY_VERIFIED");
        assertThat(html).doesNotContain("/caller.html");
        assertThat(html).doesNotContain("/command.html");
        assertThat(html).doesNotContain("live Keycloak");
        assertThat(html).doesNotContain("JSON.stringify");

        assertThat(js).contains("BUSINESS_NOC");
        assertThat(js).contains("/connect-accounts");
        assertThat(js).contains("/api/consent/requests");
        assertThat(js).contains("/api/journeys/");
        assertThat(page("shared/records.js")).contains("/issued-records");
        assertThat(html).contains("/shared/records.js");
        assertThat(js).contains("INDUSTRY");
        assertThat(js).contains("X-Auth-Jti");
        assertThat(js).contains("/retry");
        assertThat(js).doesNotContain("POST_MATRIC_SCHOLARSHIP");
        assertThat(js).doesNotContain("PARTIALLY_VERIFIED");
        assertThat(js).doesNotContain("/api/connector/chaos");
        assertThat(js).doesNotContain("/caller.html");

        assertThat(css).contains(":focus-visible");
        assertThat(css).contains("min-height: 48px");
        assertThat(css).doesNotContain("IBM Plex");
    }

    @Test
    void citizenServicesDirectoryListsThreeIndependentPortals() {
        String root = page("index.html");
        assertThat(root).contains("Citizen services");
        assertThat(root).contains("/scholarship/");
        assertThat(root).contains("/licence/");
        assertThat(root).contains("/farmer/");
        assertThat(root).contains("Scholarship");
        assertThat(root).contains("Business licence");
        assertThat(root).contains("Farmer subsidy");
        assertThat(root).contains("Government of Maharashtra");
        assertThat(root).doesNotContain("Control plane");
        assertThat(root).doesNotContain("nav-tools");
        assertThat(root).doesNotContain("http-equiv=\"refresh\"");
        assertThat(root).doesNotContain("/caller.html");
        assertThat(root).doesNotContain("/command.html");
    }

    private static String page(String name) {
        try (InputStream in = LicencePortalStaticPagesTest.class.getResourceAsStream("/static/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(name, e);
        }
    }
}
