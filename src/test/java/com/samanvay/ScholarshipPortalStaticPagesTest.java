package com.samanvay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ScholarshipPortalStaticPagesTest {

    @Test
    void portalIsAGovernmentServiceSkinNotTheControlPlane() {
        String html = page("scholarship/index.html");
        String css = page("scholarship/portal.css");
        String js = page("scholarship/portal.js");

        assertThat(html).contains("Government of Maharashtra");
        assertThat(html).contains("Scholarship Services");
        assertThat(html).containsIgnoringCase("महाराष्ट्र");
        assertThat(html).contains("Apply for scholarship");
        assertThat(html).contains("Connect accounts");
        assertThat(html).contains("Revenue");
        assertThat(html).contains("Education");
        assertThat(html).contains("DBT");
        assertThat(html).contains("DigiLocker sandbox");
        assertThat(html).contains("not live");
        assertThat(html).containsIgnoringCase("OTP demo");
        assertThat(html).contains("share income");
        assertThat(html).contains("caste");
        assertThat(html).contains("marks");
        assertThat(html).contains("bank");
        assertThat(html).contains("Officer");
        assertThat(html).contains("Review applications");
        assertThat(html).contains("Skip to main content");
        assertThat(html).contains("<main");
        assertThat(html).contains("lang=\"hi\"");
        assertThat(html).contains("AUTH STUBBED");
        assertThat(html).contains("not live SSO");
        assertThat(html).contains("Linked");
        assertThat(html).contains("of 3");
        assertThat(html).doesNotContain("Control plane");
        assertThat(html).doesNotContain("/api/connector/chaos");
        assertThat(html).doesNotContain("/api/catalog/import");
        assertThat(html).doesNotContain("Kill");
        assertThat(html).doesNotContain("JSON.stringify");
        assertThat(html).doesNotContain("<pre");
        assertThat(html).doesNotContain("X-Auth-Jti");
        assertThat(html).doesNotContain("connector ref");
        assertThat(html).doesNotContain("live Keycloak");

        assertThat(js).contains("/api/identity/links");
        assertThat(js).contains("/api/identity/citizens");
        assertThat(js).contains("/connect-accounts");
        assertThat(js).contains("/api/consent/requests");
        assertThat(js).contains("/api/journeys/");
        assertThat(js).contains("POST_MATRIC_SCHOLARSHIP");
        assertThat(js).contains("/api/applications");
        assertThat(js).contains("X-Auth-Jti");
        assertThat(js).contains("DIGILOCKER");
        assertThat(js).contains("LOCAL_ID_OTP");
        assertThat(js).contains("sandbox");
        assertThat(js).contains("000000");
        assertThat(js).contains("In progress");
        assertThat(js).contains("Needs action");
        assertThat(js).contains("Completed");
        assertThat(js).contains("Submitted");
        assertThat(js).contains("Linked ");
        assertThat(js).doesNotContain("/api/connector/chaos");
        assertThat(js).doesNotContain("/api/catalog/import");
        assertThat(js).doesNotContain("/command.html");

        assertThat(css).contains(":focus-visible");
        assertThat(css).contains("prefers-reduced-motion");
        assertThat(css).contains(":user-invalid");
        assertThat(css).contains("min-height: 48px");
        assertThat(css).contains("--sky");
        assertThat(css).doesNotContain("--olive-980");
        assertThat(css).doesNotContain("IBM Plex");
    }

    @Test
    void rootWelcomeSendsJudgesToTheScholarshipPortal() {
        String root = page("index.html");
        assertThat(root).contains("/scholarship/");
        assertThat(root).contains("Scholarship Portal");
        assertThat(root).contains("Apply for scholarship");
        assertThat(root).doesNotContain("Control plane");
        assertThat(root).doesNotContain("nav-tools");
        assertThat(root).doesNotContain("IBM Plex");
    }

    private static String page(String name) {
        try (InputStream in = ScholarshipPortalStaticPagesTest.class.getResourceAsStream("/static/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(name, e);
        }
    }
}
