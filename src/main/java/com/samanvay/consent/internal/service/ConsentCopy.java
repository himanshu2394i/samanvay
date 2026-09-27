package com.samanvay.consent.internal.service;

import com.samanvay.consent.api.DenialReason;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Properties;

/**
 * Citizen-facing consent copy, read from {@value #TABLE} (the single message table for it).
 * Reasons without copy fall back to the reason code.
 */
final class ConsentCopy {

    static final String TABLE = "consent/citizen-copy_en.properties";
    static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private static final Properties COPY = load();

    private ConsentCopy() {}

    static String denied(DenialReason reason, Instant endedAt) {
        String text = COPY.getProperty("denied." + reason.name());
        if (text == null) {
            return reason.name();
        }
        return endedAt == null ? text : text.replace("{date}", DATE.format(endedAt.atZone(DISPLAY_ZONE)));
    }

    static String statusLabel(String status) {
        return COPY.getProperty("status." + status, status);
    }

    static Properties table() {
        Properties copy = new Properties();
        copy.putAll(COPY);
        return copy;
    }

    private static Properties load() {
        try (InputStream in = ConsentCopy.class.getClassLoader().getResourceAsStream(TABLE)) {
            if (in == null) {
                throw new IllegalStateException("missing " + TABLE);
            }
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
