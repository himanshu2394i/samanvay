package com.samanvay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PhaseUiStaticPagesTest {

    @Test
    void fourSurfacesFrameInteroperabilityNotAScholarshipPortal() {
        String demo = page("demo.html");
        String command = page("command.html");
        String journey = page("journey.html");
        String incident = page("ops.html");
        String audit = page("audit.html");
        String onboard = page("onboard.html");
        String caller = page("caller.html");
        String schemes = page("schemes.html");
        String css = page("console.css");
        String js = page("console.js");

        for (String html : new String[] {demo, command, journey, incident, audit, onboard, caller, schemes}) {
            assertThat(html).containsIgnoringCase("interoperability");
            assertThat(html).doesNotContain("Apply for scholarship");
            assertThat(html).contains("journey.html");
            assertThat(html).contains("ops.html");
            assertThat(html).contains("audit.html");
            assertThat(html).contains("schemes.html");
            assertThat(html).contains("/scholarship/");
            assertThat(html).contains("/licence/");
            assertThat(html).contains("/farmer/");
            assertThat(html).contains("State operations");
            assertThat(html).contains("Government of Maharashtra");
            assertThat(html).contains("Skip to content");
            assertThat(html).contains("<main");
            assertThat(html).contains("nav-primary");
            assertThat(html).contains("nav-tools");
            assertThat(html).contains("Staff tools");
            assertThat(html).contains("/demo.html");
            assertThat(html).contains("class=\"tricolor\"");
            assertThat(html).contains("AUTH STUBBED");
            assertThat(html).contains("not live Keycloak");
        }

        assertThat(demo).contains("Start demo (external caller)");
        assertThat(demo).contains("/scholarship/");
        assertThat(demo).contains("Open Citizen services");
        assertThat(demo).contains("/farmer/");
        assertThat(demo).contains("interoperability middle layer");
        assertThat(demo).contains("Officer desk");
        assertThat(demo).contains("Published schemes");
        assertThat(demo).contains("AUTH STUBBED");
        assertThat(demo).contains("not live Keycloak");
        assertThat(demo).doesNotContain("/api/");
        assertThat(demo).doesNotContain("<script");
        assertThat(command).contains("Command");
        assertThat(command).contains("Tracked applications");
        assertThat(schemes).contains("Farmer subsidy");
        assertThat(schemes).contains("configuration");
        assertThat(journey).contains("Identity");
        assertThat(journey).contains("/api/applications/");
        assertThat(incident).contains("/api/connector/chaos/");
        assertThat(incident).contains("/api/journeys/exceptions");
        assertThat(incident).contains("/api/journeys/instances/");
        assertThat(incident).contains("/api/applications");
        assertThat(incident).contains("Affected connector");
        assertThat(incident).contains("Impacted apps");
        assertThat(incident).contains("Open exceptions");
        assertThat(incident).contains("PARTIALLY_VERIFIED");
        assertThat(incident).contains("demo profile");
        assertThat(incident).contains("Kill");
        assertThat(incident).contains(">Retry<");
        assertThat(incident).doesNotContain("id=\"retryId\"");
        assertThat(incident).doesNotContain("Retry instance");
        assertThat(incident).doesNotContain("\u2014");
        assertThat(incident).contains("details");
        assertThat(incident).contains("Chaos");
        assertThat(audit).contains("/api/audit/verify");
        assertThat(audit).contains("/api/audit/demo/tamper/");
        assertThat(audit).contains("Tamper");
        assertThat(onboard).contains("/api/catalog/import/openapi");
        assertThat(onboard).contains("Side tool");
        assertThat(onboard).contains("Which department are you onboarding?");
        assertThat(onboard).contains("Approve field matches");
        assertThat(onboard).contains("Suggest matches");
        assertThat(onboard).contains("Save approved matches");
        assertThat(onboard).contains("Import suggestions from OpenAPI (optional)");
        assertThat(onboard).contains("Normalized sample");
        assertThat(onboard).contains("INCOME_CERTIFICATE");
        assertThat(onboard).contains("CASTE_CERTIFICATE");
        assertThat(onboard).contains("MARKS");
        assertThat(onboard).contains("BANK_ACCOUNT");
        assertThat(onboard).contains("category: { code:");
        assertThat(onboard).doesNotContain("FIRE_NOC");
        assertThat(onboard).contains("Step 1 of 6");
        assertThat(onboard).doesNotContain("auto-mapped");
        assertThat(caller).contains("external caller");
        assertThat(caller).doesNotContain("Apply for scholarship");
        assertThat(caller).contains("nav-tools");
        assertThat(caller).contains("Now open Journey / Incident / Audit to see what happened inside");
        assertThat(caller).contains("AUTH STUBBED");
        assertThat(caller).contains("not live Keycloak");
        assertThat(caller).contains("X-Auth-Jti");
        assertThat(caller).contains("connect-accounts");
        assertThat(caller).contains("Connect accounts");
        assertThat(caller).contains("Connect each missing department once. Already linked departments are skipped.");
        assertThat(caller).contains("departments connected");
        assertThat(caller).contains("id=\"checklistProgress\"");
        assertThat(caller).contains("aria-live");
        assertThat(caller).contains("Already linked — skipped");
        assertThat(caller).contains("p.label");
        assertThat(caller).contains("p.kind");
        assertThat(caller).contains("DIGILOCKER");
        assertThat(caller).contains("sandbox");
        assertThat(caller).contains("LOCAL_ID_OTP");
        assertThat(caller).doesNotContain("Link + consent from policy");
        assertThat(caller).doesNotContain("proof: \"stub\"");
        assertThat(caller).doesNotContain("location.href");
        assertThat(css).contains(":focus-visible");
        assertThat(css).contains("prefers-reduced-motion");
        assertThat(css).contains("table-wrap");
        assertThat(css).contains("--color-primary");
        assertThat(css).contains("--color-danger");
        assertThat(css).contains("--color-secondary");
        assertThat(css).contains("@font-face");
        assertThat(css).contains("IBM Plex Sans");
        assertThat(css).contains("IBM Plex Mono");
        assertThat(css).contains("tabular-nums");
        assertThat(css).contains("scrollbar-color");
        assertThat(css).contains("::selection");
        assertThat(css).contains("font-size-adjust");
        assertThat(js).contains("async function api(");
        assertThat(js).contains("function confirmDanger(");
        assertThat(js).contains("function friendlyError(");
        assertThat(js).contains("function setBusy(");
        assertThat(js).contains("function tableHtml(");
        assertThat(js).contains("function skeletonTiles(");
        assertThat(js).contains("function setSpine(");
        assertThat(js).contains("The control plane API did not answer");
        assertThat(command).contains("Live telemetry");
        assertThat(command).contains("id=\"plane\"");
        assertThat(journey).contains("id=\"beats\"");
        assertThat(incident).contains("id=\"incidentSpine\"");
        assertThat(audit).contains("TAMPER DETECTED");
        assertThat(audit).contains("CHAIN INTACT");
        assertThat(PhaseUiStaticPagesTest.class.getResource("/static/fonts/plex-sans-latin.woff2"))
                .as("IBM Plex Sans latin")
                .isNotNull();
        assertThat(PhaseUiStaticPagesTest.class.getResource("/static/fonts/plex-mono-400.woff2"))
                .as("IBM Plex Mono")
                .isNotNull();
    }

    private static String page(String name) {
        try (InputStream in = PhaseUiStaticPagesTest.class.getResourceAsStream("/static/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(name, e);
        }
    }
}
