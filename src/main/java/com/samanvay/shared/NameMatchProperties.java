package com.samanvay.shared;

import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable inputs to the {@link NameMatcher}. The only knob today is the honorific
 * list ({@code samanvay.name-match.honorifics}); left unset it falls back to
 * {@link NameMatcher#DEFAULT_HONORIFICS}. The rules themselves live in code and
 * are versioned by {@link NameMatcher#VERSION}.
 */
@ConfigurationProperties("samanvay.name-match")
public record NameMatchProperties(List<String> honorifics) {

    public Set<String> honorificsOrDefault() {
        return honorifics == null || honorifics.isEmpty()
                ? NameMatcher.DEFAULT_HONORIFICS
                : Set.copyOf(honorifics);
    }
}
