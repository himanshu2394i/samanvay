package com.samanvay.connector.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ConnectorNeverSeesSigningKeyTest {

    @Test
    void connectorSourcesDoNotResolveSigningKey() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.samanvay.connector");
        classes.forEach(c -> assertThat(c.getSource().map(s -> s.getUri().toString()).orElse(""))
                .doesNotContain("this-is-not-a-source-check"));
        String joined = classes.stream()
                .flatMap(c -> c.getCodeUnitNames().stream())
                .reduce("", (a, b) -> a + b);
        assertThat(joined).doesNotContain("consent-grant-signing-key");
    }
}
