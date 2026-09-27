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
 * Consent copy. Citizen-facing strings are read from {@value #TABLE}, officer-facing strings
 * from {@value #OFFICER_TABLE}; each is the single message table for its audience, and both
 * sit under src/main so the completeness test and the banned-phrase scan cover them.
 * Reasons without copy fall back to the reason code.
 */
final class ConsentCopy {

    static final String TABLE = "consent/citizen-copy_en.properties";
    static final String OFFICER_TABLE = "consent/officer-copy_en.properties";
    static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private static final Properties COPY = load(TABLE);
    private static final Properties OFFICER = load(OFFICER_TABLE);

    private ConsentCopy() {}

    static String denied(DenialReason reason, Instant endedAt) {
        String text = COPY.getProperty("denied." + reason.name());
        if (text == null) {
            return reason.name();
        }
        return endedAt == null ? text : text.replace("{date}", DATE.format(endedAt.atZone(DISPLAY_ZONE)));
    }

    /** Copy shown to the officer whose check was refused; falls back to the reason code. */
    static String officerDenied(DenialReason reason) {
        return OFFICER.getProperty("denied." + reason.name(), reason.name());
    }

    static String statusLabel(String status) {
        return COPY.getProperty("status." + status, status);
    }

    static Properties table() {
        Properties copy = new Properties();
        copy.putAll(COPY);
        return copy;
    }

    static Properties officerTable() {
        Properties copy = new Properties();
        copy.putAll(OFFICER);
        return copy;
    }

    private static Properties load(String table) {
        try (InputStream in = ConsentCopy.class.getClassLoader().getResourceAsStream(table)) {
            if (in == null) {
                throw new IllegalStateException("missing " + table);
            }
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
