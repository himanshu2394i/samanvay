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
        assertThat(html).contains("Officer login");
        assertThat(html).contains("demonstration — not SSO");
        assertThat(html).contains("Retry");
        assertThat(html).contains("Mark Revenue records unavailable");
        assertThat(html).contains("Restore Revenue records");
        assertThat(html).contains("department records");
        assertThat(html).contains("id=\"officerLogin\"");
        assertThat(html).contains("id=\"officerCase\"");
        assertThat(html).contains("id=\"langEn\"");
        assertThat(html).contains("id=\"langMr\"");
        assertThat(html).contains("data-i18n");
        assertThat(html).contains("Skip to main content");
        assertThat(html).contains("<main");
        assertThat(html).contains("lang=\"mr\"");
        assertThat(html).contains("AUTH STUBBED");
        assertThat(html).contains("not live SSO");
        assertThat(html).contains("Linked");
        assertThat(html).contains("of 3");
        assertThat(html).contains("/demo.html");
        assertThat(html).contains("/licence/");
        assertThat(html).contains("/farmer/");
        assertThat(html).doesNotContain("Control plane");
        assertThat(html).doesNotContain("/api/connector/chaos");
        assertThat(html).doesNotContain("/api/catalog/import");
        assertThat(html).doesNotContain("Kill");
        assertThat(html).doesNotContain("PARTIALLY_VERIFIED");
        assertThat(html).doesNotContain("/caller.html");
        assertThat(html).doesNotContain("/command.html");
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
        String shared = page("shared/records.js");
        assertThat(shared).contains("/issued-records");
        assertThat(shared).contains("/api/connector/issued-documents");
        assertThat(shared).contains("DigiLocker");
        assertThat(shared).contains("not stored");
        assertThat(html).contains("/shared/records.js");
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
        assertThat(js).contains("/api/journeys/instances/");
        assertThat(js).contains("/retry");
        assertThat(js).contains("/api/connector/chaos/");
        assertThat(js).contains("revenue-rest-mock");
        assertThat(js).contains("applyLang");
        assertThat(js).contains("officerDemo");
        assertThat(js).doesNotContain("PARTIALLY_VERIFIED");
        assertThat(js).doesNotContain("/api/catalog/import");
        assertThat(js).doesNotContain("/command.html");
        assertThat(js).doesNotContain("/caller.html");

        assertThat(css).contains(":focus-visible");
        assertThat(css).contains("prefers-reduced-motion");
        assertThat(css).contains(":user-invalid");
        assertThat(css).contains("min-height: 48px");
        assertThat(css).contains("--sky");
        assertThat(css).contains(".service-grid");
        assertThat(css).doesNotContain("--olive-980");
        assertThat(css).doesNotContain("IBM Plex");
    }

    @Test
    void rootDirectoryLinksScholarshipAsJudgePath() {
        String root = page("index.html");
        assertThat(root).contains("/scholarship/");
        assertThat(root).contains("Scholarship");
        assertThat(root).contains("/licence/");
        assertThat(root).contains("/farmer/");
        assertThat(root).contains("Citizen services");
        assertThat(root).doesNotContain("Control plane");
        assertThat(root).doesNotContain("nav-tools");
        assertThat(root).doesNotContain("IBM Plex");
        assertThat(root).doesNotContain("/caller.html");
        assertThat(root).doesNotContain("/command.html");
    }

    @Test
    void publishedSchemesCutawayShowsFarmerSubsidyAsConfiguration() {
        String html = page("schemes.html");
        assertThat(html).contains("Published schemes");
        assertThat(html).contains("Government of Maharashtra");
        assertThat(html).contains("State operations");
        assertThat(html).contains("/api/catalog/journeys");
        assertThat(html).contains("FARMER_SUBSIDY");
        assertThat(html).contains("configuration");
        assertThat(html).contains("no new Java");
        assertThat(html).contains("/scholarship/");
        assertThat(html).contains("/licence/");
        assertThat(html).contains("/farmer/");
        assertThat(html).doesNotContain("Apply for scholarship");
        assertThat(html).doesNotContain("no farmer portal");
        assertThat(html).doesNotContain("no licence website");
        assertThat(html).contains("AUTH STUBBED");
        assertThat(html).contains("not live Keycloak");
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
