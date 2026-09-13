package com.samanvay.connector.internal.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SoapAdapterEscapingTest {

    @Test
    void specialCharactersAreEscaped() {
        SoapAdapter adapter = new SoapAdapter(new MockDepartmentBackend());
        String rendered = adapter.renderTemplate("<n>{{name}}</n>", Map.of("name", "A&B<C\""));
        assertThat(rendered).contains("A&amp;B&lt;C&quot;").doesNotContain("A&B<C\"");
    }
}
