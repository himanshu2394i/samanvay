package com.samanvay.shared;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic name matcher for source name-verification (bank, and later ULI /
 * land records). One copy lives in {@code shared}; live adapters call it when a
 * source returns the holder's name, and the holder's name is thrown away right
 * after — it never leaves the adapter (see #31's leak test).
 *
 * <p>The rules follow the Principal Architect's ruling on #31:
 * <ol>
 *   <li>Normalise first: case, punctuation, extra spaces, honorifics, and the
 *       order of the name parts.
 *   <li>An initial counts only against a full part starting with the same letter.
 *   <li>All parts agree, no initial relied on -&gt; {@link NameMatchResult#MATCH}.
 *   <li>Two or more parts agree (or an initial carried an otherwise-full match)
 *       -&gt; {@link NameMatchResult#PARTIAL}.
 *   <li>Otherwise -&gt; {@link NameMatchResult#NO_MATCH}.
 *   <li>Different scripts, or nothing to compare -&gt;
 *       {@link NameMatchResult#NOT_CHECKED}.
 * </ol>
 *
 * <p>Two edge cases the ruling left open are decided here, both towards a human
 * review rather than a silent match: a match that relies on any initial is
 * {@code PARTIAL} at most, and because order is normalised away, "surname plus
 * one other" is read as "at least two aligned parts".
 *
 * <p>{@link #VERSION} is recorded with each decision so a later rule change shows
 * up as a different version rather than a silently different verdict.
 */
public final class NameMatcher {

    /** Bump when the rules or the honorific handling change. */
    public static final String VERSION = "name-match-v1";

    /** Titles stripped before matching. Latin forms are matched case-insensitively. */
    public static final Set<String> DEFAULT_HONORIFICS = Set.of(
            "mr", "mrs", "ms", "miss", "mstr", "master", "dr", "prof",
            "shri", "sri", "smt", "kum", "kumari", "sushri", "sushree", "late",
            "श्री", "श्रीमती", "कुमारी", "कु", "डॉ");

    private final Set<String> honorifics;

    public NameMatcher(Collection<String> honorifics) {
        Set<String> h = new LinkedHashSet<>();
        for (String s : honorifics) {
            h.add(fold(s));
        }
        this.honorifics = Set.copyOf(h);
    }

    public static NameMatcher withDefaultHonorifics() {
        return new NameMatcher(DEFAULT_HONORIFICS);
    }

    public String version() {
        return VERSION;
    }

    public NameMatchResult match(String recorded, String provided) {
        Script sa = scriptOf(recorded);
        Script sb = scriptOf(provided);
        // Different scripts (e.g. Devanagari vs Latin) are not compared here.
        if (sa == Script.MIXED || sb == Script.MIXED || (sa != Script.EMPTY && sb != Script.EMPTY && sa != sb)) {
            return NameMatchResult.NOT_CHECKED;
        }
        List<Part> a = parts(recorded);
        List<Part> b = parts(provided);
        if (a.isEmpty() || b.isEmpty()) {
            return NameMatchResult.NOT_CHECKED;
        }

        boolean[] usedB = new boolean[b.size()];
        boolean[] usedA = new boolean[a.size()];
        int aligned = 0;

        // Pass 1: full-part to identical full-part. Full matches win first so an
        // initial can't steal a part that has an exact twin.
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).initial) continue;
            for (int j = 0; j < b.size(); j++) {
                if (usedB[j] || b.get(j).initial) continue;
                if (a.get(i).text.equals(b.get(j).text)) {
                    usedA[i] = usedB[j] = true;
                    aligned++;
                    break;
                }
            }
        }

        // Pass 2: anything left where at least one side is an initial.
        boolean reliedOnInitial = false;
        for (int i = 0; i < a.size(); i++) {
            if (usedA[i]) continue;
            for (int j = 0; j < b.size(); j++) {
                if (usedB[j]) continue;
                if (initialCompatible(a.get(i), b.get(j))) {
                    usedA[i] = usedB[j] = true;
                    aligned++;
                    reliedOnInitial = true;
                    break;
                }
            }
        }

        boolean allAligned = aligned == a.size() && aligned == b.size();
        if (allAligned && !reliedOnInitial) {
            return NameMatchResult.MATCH;
        }
        return aligned >= 2 ? NameMatchResult.PARTIAL : NameMatchResult.NO_MATCH;
    }

    /** True when the pair agrees and at least one side is an initial. */
    private static boolean initialCompatible(Part x, Part y) {
        if (!x.initial && !y.initial) {
            return false; // handled in pass 1
        }
        return x.text.charAt(0) == y.text.charAt(0);
    }

    private List<Part> parts(String name) {
        List<Part> out = new ArrayList<>();
        if (name == null) return out;
        for (String token : fold(name).split("[^\\p{L}]+")) {
            if (token.isBlank() || honorifics.contains(token)) continue;
            out.add(new Part(token, token.length() == 1));
        }
        return out;
    }

    /** Lower-case and strip accents; keeps letters of any script. */
    private static String fold(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
        return n.toLowerCase();
    }

    private enum Script { EMPTY, LATIN, DEVANAGARI, MIXED }

    /** Which script a name is written in, ignoring digits, spaces and punctuation. */
    private static Script scriptOf(String name) {
        if (name == null) return Script.EMPTY;
        Script found = Script.EMPTY;
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            Script s = switch (Character.UnicodeScript.of(cp)) {
                case LATIN -> Script.LATIN;
                case DEVANAGARI -> Script.DEVANAGARI;
                default -> Script.MIXED; // an unhandled script: send to a human
            };
            if (found == Script.EMPTY) {
                found = s;
            } else if (found != s) {
                return Script.MIXED;
            }
        }
        return found;
    }

    private record Part(String text, boolean initial) {}
}
