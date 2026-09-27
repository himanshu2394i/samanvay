package com.samanvay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Naming rule (legal): the forbidden two-word phrase (see {@link #FORBIDDEN})
 * must not appear anywhere we ship or publish - Java, resources, static
 * HTML/JS/CSS, config, migrations, the Keycloak provider and realm exports,
 * docs and the README. Use "permissions" or the existing consent naming
 * instead.
 */
class NoConsentManagerPhraseTest {

    private static final String FORBIDDEN = String.join(" ", "consent", "manager");

    static final List<Path> ROOTS = List.of(
            Path.of("src", "main"), Path.of("keycloak"), Path.of("docs"), Path.of("README.md"),
            Path.of("docker-compose.yml"));

    @Test
    void phraseDoesNotAppearInShippedOrPublishedFiles() throws IOException {
        List<String> hits = new ArrayList<>();
        int scanned = 0;
        for (Path root : ROOTS) {
            assertThat(root).as("scan root").exists();
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String text;
                    try {
                        text = Files.readString(file, StandardCharsets.UTF_8);
                    } catch (MalformedInputException binary) {
                        continue; // fonts and other binaries
                    }
                    scanned++;
                    if (containsForbidden(text)) {
                        hits.add(file.toString());
                    }
                }
            }
        }
        assertThat(scanned).isGreaterThan(100);
        assertThat(hits).as("files containing the forbidden phrase").isEmpty();
    }

    @Test
    void detectorCatchesTheUsualSpellings() {
        String c = "Consent";
        String m = "Manager";
        assertThat(containsForbidden("the " + c.toLowerCase() + " " + m.toLowerCase() + " does x")).isTrue();
        assertThat(containsForbidden(c + "\n  " + m)).isTrue();
        assertThat(containsForbidden(c + "-" + m)).isTrue();
        assertThat(containsForbidden(c + "_" + m.toUpperCase())).isTrue();
        assertThat(containsForbidden(c + m + "Service")).isTrue();
        assertThat(containsForbidden("consent service and a manager")).isFalse();
    }

    static boolean containsForbidden(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.replaceAll("[\\s_-]+", " ").contains(FORBIDDEN) || lower.contains(FORBIDDEN.replace(" ", ""));
    }
}
