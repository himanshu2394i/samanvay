package com.samanvay.catalog.internal.service;

import com.samanvay.catalog.api.MappingSuggestion;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

class MappingSuggestor {

    List<MappingSuggestion> suggest(List<String> sources, List<String> targets) {
        List<MappingSuggestion> out = new ArrayList<>();
        for (String target : targets) {
            String bestSource = null;
            double best = 0;
            for (String source : sources) {
                double score = score(source, target);
                if (score > best) {
                    best = score;
                    bestSource = source;
                }
            }
            if (bestSource != null && best >= 0.45) {
                out.add(new MappingSuggestion(bestSource, target, round(best), "lexical", false));
            }
        }
        out.sort(Comparator.comparingDouble(MappingSuggestion::confidence).reversed());
        return List.copyOf(out);
    }

    private static double score(String source, String target) {
        String a = norm(source);
        String b = norm(target);
        if (a.equals(b)) {
            return 1.0;
        }
        if (a.contains(b) || b.contains(a)) {
            return 0.85;
        }
        int dist = levenshtein(a, b);
        int max = Math.max(a.length(), b.length());
        return max == 0 ? 0 : 1.0 - ((double) dist / max);
    }

    static String norm(String raw) {
        String spaced = raw.replace('_', ' ').replace('-', ' ');
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < spaced.length(); i++) {
            char c = spaced.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(spaced.charAt(i - 1))) {
                sb.append(' ');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString().replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
