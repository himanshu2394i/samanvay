package com.samanvay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PhaseUiStaticPagesTest {

    @Test
    void fourSurfacesFrameInteroperabilityNotAScholarshipPortal() {
        String command = page("index.html");
        String journey = page("journey.html");
        String incident = page("ops.html");
        String audit = page("audit.html");
        String onboard = page("onboard.html");
        String caller = page("caller.html");
        String css = page("console.css");
        String js = page("console.js");

        for (String html : new String[] {command, journey, incident, audit, onboard}) {
            assertThat(html).containsIgnoringCase("interoperability");
            assertThat(html).doesNotContain("Apply for scholarship");
            assertThat(html).contains("journey.html");
            assertThat(html).contains("ops.html");
            assertThat(html).contains("audit.html");
            assertThat(html).contains("Control plane");
            assertThat(html).contains("Skip to content");
            assertThat(html).contains("<main");
            assertThat(html).contains("nav-primary");
            assertThat(html).contains("nav-tools");
        }

        assertThat(command).contains("Command");
        assertThat(command).contains("Tracked applications");
        assertThat(journey).contains("Identity");
        assertThat(journey).contains("/api/applications/");
        assertThat(incident).contains("/api/connector/chaos/");
        assertThat(incident).contains("/api/journeys/exceptions");
        assertThat(incident).contains("Kill");
        assertThat(audit).contains("/api/audit/verify");
        assertThat(audit).contains("/api/audit/demo/tamper/");
        assertThat(audit).contains("Tamper");
        assertThat(onboard).contains("/api/catalog/import/openapi");
        assertThat(onboard).contains("Side tool");
        assertThat(caller).contains("external caller");
        assertThat(caller).doesNotContain("Apply for scholarship");
        assertThat(caller).contains("nav-tools");
        assertThat(css).contains(":focus-visible");
        assertThat(css).contains("prefers-reduced-motion");
        assertThat(css).contains("table-wrap");
        assertThat(js).contains("async function api(");
        assertThat(js).contains("function confirmDanger(");
        assertThat(js).contains("function friendlyError(");
        assertThat(js).contains("function setBusy(");
        assertThat(js).contains("function tableHtml(");
        assertThat(js).contains("The control plane API did not answer");
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
