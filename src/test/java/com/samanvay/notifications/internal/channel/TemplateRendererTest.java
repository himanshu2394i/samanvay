package com.samanvay.notifications.internal.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateRendererTest {

    @Test
    void escapesInjectionAndFallsBackViaDispatcherContract() {
        TemplateRenderer renderer = new TemplateRenderer();
        String body = renderer.render(
                "en/ConsentRequested",
                Map.of(
                        "requester",
                        "INDUSTRY{{x}}",
                        "category",
                        "PROPERTY",
                        "purpose",
                        "BUSINESS_NOC",
                        "revokeUrl",
                        "/consent"));
        assertThat(body).contains("INDUSTRY{{x}}");
        assertThat(body).doesNotContain("asked for {{");
    }
}
