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
 * Naming rule (legal): the phrase "Consent Manager" must not appear anywhere in
 * shipped sources - Java, resources, static HTML/JS/CSS, config, migrations.
 * Use "permissions" or the existing consent naming instead.
 */
class NoConsentManagerPhraseTest {

    private static final String FORBIDDEN = String.join(" ", "consent", "manager");

    @Test
    void phraseDoesNotAppearInSrcMain() throws IOException {
        Path root = Path.of("src", "main");
        assertThat(root).isDirectory();
        List<String> hits = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String text;
                try {
                    text = Files.readString(file, StandardCharsets.UTF_8);
                } catch (MalformedInputException binary) {
                    continue; // fonts and other binaries
                }
                scanned++;
                String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", " ");
                if (normalized.contains(FORBIDDEN) || text.toLowerCase(Locale.ROOT).contains("consentmanager")) {
                    hits.add(file.toString());
                }
            }
        }
        assertThat(scanned).isGreaterThan(100);
        assertThat(hits).as("files containing the forbidden phrase").isEmpty();
    }
}
