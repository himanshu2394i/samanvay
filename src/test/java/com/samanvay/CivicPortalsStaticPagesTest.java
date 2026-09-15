package com.samanvay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CivicPortalsStaticPagesTest {

    @Test
    void licencePortalIsAGovernmentSkinWiredToBusinessNoc() {
        String html = page("licence/index.html");
        String js = page("licence/portal.js");

        assertThat(html).contains("Government of Maharashtra");
        assertThat(html).contains("Apply for licence");
        assertThat(html).contains("Connect accounts");
        assertThat(html).contains("Municipal");
        assertThat(html).contains("Fire");
        assertThat(html).contains("Pollution");
        assertThat(html).contains("Revenue");
        assertThat(html).contains("AUTH STUBBED");
        assertThat(html).contains("DigiLocker sandbox");
        assertThat(html).contains("Officer login");
        assertThat(html).contains("of 4");
        assertThat(html).doesNotContain("Control plane");
        assertThat(html).doesNotContain("Apply for scholarship");
        assertThat(html).doesNotContain("/caller.html");
        assertThat(html).doesNotContain("X-Auth-Jti");

        assertThat(js).contains("BUSINESS_NOC");
        assertThat(js).contains("INDUSTRY");
        assertThat(js).contains("PROPERTY");
        assertThat(js).contains("FIRE_NOC");
        assertThat(js).contains("POLLUTION_CLEARANCE");
        assertThat(js).contains("LAND_RECORD");
        assertThat(js).contains("/api/journeys/");
        assertThat(js).contains("fire-rest-mock");
        assertThat(js).contains("/licence/");
        assertThat(js).doesNotContain("POST_MATRIC_SCHOLARSHIP");
        assertThat(js).doesNotContain("FARMER_SUBSIDY");
    }

    @Test
    void farmerPortalWaitsForOperatorOnboarding() {
        String html = page("farmer/index.html");
        String js = page("farmer/portal.js");

        assertThat(html).contains("Government of Maharashtra");
        assertThat(html).contains("Farmer subsidy");
        assertThat(html).contains("Onboard");
        assertThat(html).contains("/onboard.html");
        assertThat(html).contains("journey code");
        assertThat(html).contains("AUTH STUBBED");
        assertThat(html).doesNotContain("Apply for scholarship");
        assertThat(html).doesNotContain("Control plane");
        assertThat(html).doesNotContain("X-Auth-Jti");

        assertThat(js).contains("/onboard.html");
        assertThat(js).contains("/api/catalog/journeys/");
        assertThat(js).contains("mhFarmerJourney");
        assertThat(js).doesNotContain("BUSINESS_NOC");
        assertThat(js).doesNotContain("/api/journeys/FARMER_SUBSIDY/start");
    }

    @Test
    void rootDirectoryListsTheThreeCitizenServices() {
        String root = page("index.html");
        assertThat(root).contains("/scholarship/");
        assertThat(root).contains("Apply for scholarship");
        assertThat(root).contains("/licence/");
        assertThat(root).contains("Apply for licence");
        assertThat(root).contains("/farmer/");
        assertThat(root).contains("Farmer subsidy");
        assertThat(root).doesNotContain("Control plane");
        assertThat(root).doesNotContain("IBM Plex");
        assertThat(root).doesNotContain("/caller.html");
    }

    private static String page(String name) {
        try (InputStream in = CivicPortalsStaticPagesTest.class.getResourceAsStream("/static/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(name, e);
        }
    }
}
