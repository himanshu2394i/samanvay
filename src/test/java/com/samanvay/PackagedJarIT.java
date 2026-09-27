package com.samanvay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The repackaged boot jar (built before failsafe runs) carries every runtime
 * library and no test-only one. A test-scoped declaration of a transitive
 * runtime dependency (e.g. jboss-logging, needed by Hibernate) passes every
 * test yet makes {@code java -jar} fail at startup.
 */
class PackagedJarIT {

    @Test
    void bootJarHasRuntimeLibrariesAndNoTestOnlyOnes() throws IOException {
        Path jar;
        try (Stream<Path> files = Files.list(Path.of("target"))) {
            jar = files.filter(f -> f.getFileName().toString().matches("samanvay-core-.*\\.jar"))
                    .filter(f -> !f.getFileName().toString().endsWith("-plain.jar"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no packaged samanvay-core jar in target/"));
        }
        List<String> libs;
        try (JarFile jf = new JarFile(jar.toFile())) {
            libs = jf.stream()
                    .map(e -> e.getName())
                    .filter(n -> n.startsWith("BOOT-INF/lib/"))
                    .map(n -> n.substring("BOOT-INF/lib/".length()))
                    .toList();
        }
        assertThat(libs).as("runtime libraries in " + jar)
                .anyMatch(n -> n.startsWith("jboss-logging-"))
                .anyMatch(n -> n.startsWith("hibernate-core-"))
                .anyMatch(n -> n.startsWith("micrometer-core-"));
        assertThat(libs).as("test-only libraries in " + jar)
                .noneMatch(n -> n.startsWith("keycloak-"))
                .noneMatch(n -> n.startsWith("junit-"))
                .noneMatch(n -> n.startsWith("testcontainers-"));
    }
}
