package com.samanvay.identity.internal.service;

import com.samanvay.identity.api.Profile;
import java.time.LocalDate;
import java.util.Locale;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
class CandidateScorer {

    double score(Profile profile, JsonNode record) {
        double name = Math.max(
                jaroWinkler(profile.nameLatin(), text(record, "name")),
                jaroWinkler(latinize(profile.nameDevanagari()), text(record, "name")));
        double dob = compareDob(profile.dob(), profile.dobPrecision(), record);
        double father = profile.fatherName() == null
                ? 0.0
                : jaroWinkler(profile.fatherName(), text(record, "fatherName"));
        return 0.4 * name + 0.3 * dob + 0.2 * father + 0.1;
    }

    private static String text(JsonNode record, String field) {
        return record.get(field) == null ? "" : record.get(field).asString();
    }

    private double compareDob(LocalDate dob, String precision, JsonNode record) {
        JsonNode node = record.get("dob");
        if (node == null || node.asString().isBlank()) {
            return 0.0;
        }
        LocalDate recordDob = LocalDate.parse(node.asString().length() == 4 ? node.asString() + "-01-01" : node.asString());
        return switch (precision) {
            case "DAY" -> dob.equals(recordDob) ? 1.0 : 0.0;
            case "MONTH" -> dob.getYear() == recordDob.getYear() && dob.getMonth() == recordDob.getMonth() ? 1.0 : 0.0;
            default -> dob.getYear() == recordDob.getYear() ? 1.0 : 0.0;
        };
    }

    static String latinize(String devanagari) {
        if (devanagari == null || devanagari.isBlank()) {
            return "";
        }
        // ponytail: tiny closed table for Journey 1 names; full transliteration if more scripts appear
        return switch (devanagari) {
            case "रमेश" -> "ramesh";
            default -> devanagari;
        };
    }

    static double jaroWinkler(String a, String b) {
        if (a == null || b == null) {
            return 0.0;
        }
        String x = a.toLowerCase(Locale.ROOT);
        String y = b.toLowerCase(Locale.ROOT);
        if (x.equals(y)) {
            return 1.0;
        }
        if (x.isEmpty() || y.isEmpty()) {
            return 0.0;
        }
        int matchDistance = Math.max(x.length(), y.length()) / 2 - 1;
        boolean[] xMatch = new boolean[x.length()];
        boolean[] yMatch = new boolean[y.length()];
        int matches = 0;
        for (int i = 0; i < x.length(); i++) {
            int start = Math.max(0, i - matchDistance);
            int end = Math.min(i + matchDistance + 1, y.length());
            for (int j = start; j < end; j++) {
                if (yMatch[j] || x.charAt(i) != y.charAt(j)) {
                    continue;
                }
                xMatch[i] = true;
                yMatch[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) {
            return 0.0;
        }
        int t = 0;
        int k = 0;
        for (int i = 0; i < x.length(); i++) {
            if (!xMatch[i]) {
                continue;
            }
            while (!yMatch[k]) {
                k++;
            }
            if (x.charAt(i) != y.charAt(k)) {
                t++;
            }
            k++;
        }
        double m = matches;
        double jaro = (m / x.length() + m / y.length() + (m - t / 2.0) / m) / 3.0;
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(x.length(), y.length())); i++) {
            if (x.charAt(i) == y.charAt(i)) {
                prefix++;
            } else {
                break;
            }
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }
}
