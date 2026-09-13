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
            assertThat(html).contains("interoperability");
            assertThat(html).doesNotContain("Apply for scholarship");
            assertThat(html).contains("journey.html");
            assertThat(html).contains("ops.html");
            assertThat(html).contains("audit.html");
        }

        assertThat(command).contains("Command");
        assertThat(journey).contains("Identity");
        assertThat(journey).contains("/api/applications/");
        assertThat(incident).contains("/api/connector/chaos/");
        assertThat(incident).contains("/api/journeys/exceptions");
        assertThat(audit).contains("/api/audit/verify");
        assertThat(audit).contains("/api/audit/demo/tamper/");
        assertThat(onboard).contains("/api/catalog/import/openapi");
        assertThat(caller).contains("external caller");
        assertThat(caller).doesNotContain("Apply for scholarship");
        assertThat(css).isNotBlank();
        assertThat(js).contains("/api/");
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
