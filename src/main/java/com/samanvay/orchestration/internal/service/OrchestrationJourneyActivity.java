package com.samanvay.orchestration.internal.service;

import com.samanvay.orchestration.api.JourneyActivity;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class OrchestrationJourneyActivity implements JourneyActivity {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> RUNNING = Set.of("SUBMITTED", "PARTIALLY_VERIFIED", "VERIFIED");

    private final JdbcTemplate jdbc;

    OrchestrationJourneyActivity(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Activity activity(String journeyCode, Instant since, int recentLimit) {
        long[] counts = new long[4]; // running, completed, failed, started since the cutoff
        jdbc.query(
                "SELECT status, count(*) AS n, count(*) FILTER (WHERE created_at >= ?) AS recent FROM orchestration_instance"
                        + " WHERE journey_code = ? GROUP BY status",
                rs -> {
                    String status = rs.getString("status");
                    long n = rs.getLong("n");
                    if (RUNNING.contains(status)) {
                        counts[0] += n;
                    } else if ("APPROVED".equals(status)) {
                        counts[1] += n;
                    } else if ("REJECTED".equals(status)) {
                        counts[2] += n;
                    }
                    counts[3] += rs.getLong("recent");
                },
                Timestamp.from(since),
                journeyCode);
        List<Recent> recent = jdbc.query(
                "SELECT id, status, created_at, pinned_connector_versions::text AS pinned FROM orchestration_instance"
                        + " WHERE journey_code = ? ORDER BY created_at DESC LIMIT ?",
                (rs, n) -> new Recent(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(),
                        pinned(rs.getString("pinned"))),
                journeyCode,
                recentLimit);
        return new Activity(counts[0], counts[1], counts[2], counts[3], recent);
    }

    private static Map<String, Integer> pinned(String json) {
        Map<String, Integer> out = new HashMap<>();
        if (json != null) {
            JsonNode node = JSON.readTree(json);
            node.properties().forEach(e -> out.put(e.getKey(), e.getValue().asInt()));
        }
        return out;
    }
}
