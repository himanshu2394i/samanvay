package com.samanvay.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.Outcome;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {

    @Test
    void sameLogicalEntry_isStableAcrossMapInsertionOrderAndMapperInstances() {
        AuditEntry first = entry(map("z", 1, "a", 2));
        AuditEntry second = entry(map("a", 2, "z", 1));

        CanonicalJson one = new CanonicalJson();
        CanonicalJson two = new CanonicalJson();

        assertThat(one.serialize(first)).isEqualTo(two.serialize(second));
        assertThat(one.serialize(first)).contains("\"a\":2");
        assertThat(one.serialize(Map.of("later", Instant.parse("2026-09-05T00:00:00Z"))))
                .contains("2026-09-05T00:00:00Z")
                .doesNotContain("1757030400");
    }

    private static AuditEntry entry(Map<String, Object> meta) {
        return new AuditEntry(
                ActorType.SYSTEM,
                "phase0-ping",
                "PING",
                "subject-1",
                null,
                null,
                null,
                null,
                Outcome.ALLOWED,
                null,
                meta);
    }

    private static Map<String, Object> map(Object... kv) {
        var out = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }
}
