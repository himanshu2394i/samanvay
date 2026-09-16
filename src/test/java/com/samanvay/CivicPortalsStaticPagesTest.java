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
        assertThat(html).contains("/scholarship/");
        assertThat(html).contains("/farmer/");
        assertThat(js).doesNotContain("POST_MATRIC_SCHOLARSHIP");
        assertThat(js).doesNotContain("FARMER_SUBSIDY");
    }

    @Test
    void farmerPortalIsAGovernmentSkinWiredToFarmerSubsidy() {
        String html = page("farmer/index.html");
        String js = page("farmer/portal.js");

        assertThat(html).contains("Government of Maharashtra");
        assertThat(html).contains("Farmer subsidy");
        assertThat(html).contains("Apply");
        assertThat(html).contains("Connect accounts");
        assertThat(html).contains("Revenue");
        assertThat(html).contains("Agriculture");
        assertThat(html).contains("DBT");
        assertThat(html).contains("AUTH STUBBED");
        assertThat(html).contains("DigiLocker sandbox");
        assertThat(html).contains("Officer login");
        assertThat(html).contains("of 3");
        assertThat(html).contains("/scholarship/");
        assertThat(html).contains("/licence/");
        assertThat(html).doesNotContain("Control plane");
        assertThat(html).doesNotContain("Apply for scholarship");
        assertThat(html).doesNotContain("/caller.html");
        assertThat(html).doesNotContain("X-Auth-Jti");
        assertThat(html).doesNotContain("id=\"journeyPick\"");
        assertThat(html).doesNotContain("Bind a catalog journey");

        assertThat(js).contains("FARMER_SUBSIDY");
        assertThat(js).contains("AGRICULTURE");
        assertThat(js).contains("LAND_PARCEL");
        assertThat(js).contains("CROP_RECORD");
        assertThat(js).contains("BANK_ACCOUNT");
        assertThat(js).contains("/api/journeys/");
        assertThat(js).contains("agriculture-rest-mock");
        assertThat(js).contains("/farmer/");
        assertThat(js).doesNotContain("POST_MATRIC_SCHOLARSHIP");
        assertThat(js).doesNotContain("BUSINESS_NOC");
        assertThat(js).doesNotContain("mhFarmerJourney");
        assertThat(js).doesNotContain("id=\"journeyPick\"");
    }

    @Test
    void rootDirectoryListsTheThreeCitizenServices() {
        String root = page("index.html");
        assertThat(root).contains("/scholarship/");
        assertThat(root).contains("Apply for scholarship");
        assertThat(root).contains("/licence/");
        assertThat(root).contains("Apply for licence");
        assertThat(root).contains("/farmer/");
        assertThat(root).contains("Apply for farmer subsidy");
        assertThat(root).contains("service-grid");
        assertThat(root).contains("service-card");
        assertThat(root).contains("class=\"util\"");
        assertThat(root).contains("/demo.html");
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
