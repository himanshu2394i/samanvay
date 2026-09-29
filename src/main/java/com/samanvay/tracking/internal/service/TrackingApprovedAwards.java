package com.samanvay.tracking.internal.service;

import com.samanvay.consent.api.ApprovedAwards;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Tracking owns application status, so it answers consent's "approved awards" question. */
@Component
class TrackingApprovedAwards implements ApprovedAwards {

    private final JdbcTemplate jdbc;

    TrackingApprovedAwards(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Award> forCitizen(UUID citizenId) {
        return jdbc.query(
                """
                SELECT reference_no, journey_code, COALESCE(closed_at, submitted_at) AS decided_at
                FROM tracking_application
                WHERE citizen_id = ? AND status = 'APPROVED'
                ORDER BY decided_at DESC
                """,
                (rs, i) -> new Award(
                        rs.getString("reference_no"),
                        rs.getString("journey_code"),
                        rs.getTimestamp("decided_at").toInstant()),
                citizenId);
    }
}
