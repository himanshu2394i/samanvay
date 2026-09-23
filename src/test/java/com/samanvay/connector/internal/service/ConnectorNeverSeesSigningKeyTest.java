package com.samanvay.connector.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ConnectorNeverSeesSigningKeyTest {

    @Test
    void connectorSourcesDoNotResolveSigningKey() throws IOException {
        Path root = Path.of("src/main/java/com/samanvay/connector");
        StringBuilder all = new StringBuilder();
        try (var walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    all.append(Files.readString(p));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        assertThat(all.toString()).doesNotContain("consent-grant-signing-key");
    }
}
