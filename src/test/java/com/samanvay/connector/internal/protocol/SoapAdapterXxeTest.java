package com.samanvay.connector.internal.protocol;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SoapAdapterXxeTest {

    @Test
    void externalEntityDoesNotReadFile() {
        SoapAdapter adapter = new SoapAdapter(new MockDepartmentBackend());
        byte[] xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <root>&xxe;</root>
                """.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> adapter.parseSafely(xxe)).isInstanceOf(IllegalArgumentException.class);
    }
}
