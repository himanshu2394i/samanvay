package com.samanvay.payments.internal.service;

import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.payments.api.Disbursement;
import com.samanvay.payments.api.DisbursementService;
import com.samanvay.payments.api.Instalment;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mock DBT (HLD 1.5): records the disbursement of an approved application and gives every
 * instalment an id. No payment rail is called and no amount is held.
 *
 * <p>Idempotent: {@code payments_disbursement.application_id} is UNIQUE and the insert is
 * {@code ON CONFLICT DO NOTHING}, so a concurrent or redelivered call inserts nothing, writes
 * no instalments and no audit entry, and returns the disbursement that already exists. The
 * audit entry ({@code DISBURSEMENT_ISSUED}) is written in the same transaction as the rows.
 */
@Service
class DefaultDisbursementService implements DisbursementService {

    static final String STATUS_ISSUED = "ISSUED";
    static final String INSTALMENT_SCHEDULED = "SCHEDULED";
    static final int MAX_INSTALMENTS = 12;

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;
    private final int instalmentCount;

    DefaultDisbursementService(
            JdbcTemplate jdbc,
            AuditService audit,
            Clock clock,
            @Value("${samanvay.payments.instalments:2}") int instalmentCount) {
        if (instalmentCount < 1 || instalmentCount > MAX_INSTALMENTS) {
            throw new IllegalStateException("samanvay.payments.instalments must be between 1 and "
                    + MAX_INSTALMENTS + " (got " + instalmentCount + ")");
        }
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.instalmentCount = instalmentCount;
    }

    @Override
    @Transactional
    public Disbursement disburse(UUID applicationId, UUID citizenId, String journeyCode) {
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(citizenId, "citizenId");
        if (journeyCode == null || journeyCode.isBlank()) {
            throw new IllegalArgumentException("journeyCode is required");
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS); // what Postgres stores
        int inserted = jdbc.update(
                """
                INSERT INTO payments_disbursement (id, application_id, citizen_id, journey_code, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT payments_disbursement_one_per_application DO NOTHING
                """,
                id, applicationId, citizenId, journeyCode, STATUS_ISSUED, Timestamp.from(now));
        if (inserted == 0) {
            return forApplication(applicationId).orElseThrow();
        }
        List<Instalment> instalments = new ArrayList<>();
        for (int seq = 1; seq <= instalmentCount; seq++) {
            UUID instalmentId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO payments_instalment (id, disbursement_id, sequence_no, status, created_at)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    instalmentId, id, seq, INSTALMENT_SCHEDULED, Timestamp.from(now));
            instalments.add(new Instalment(instalmentId, seq, INSTALMENT_SCHEDULED));
        }
        audit.record(new AuditEntry(
                ActorType.SYSTEM,
                "payments",
                "DISBURSEMENT_ISSUED",
                citizenId.toString(),
                "disbursement",
                null,
                null,
                null,
                Outcome.ALLOWED,
                null,
                Map.of(
                        "disbursementId", id.toString(),
                        "applicationId", applicationId.toString(),
                        "journeyCode", journeyCode,
                        "instalmentCount", String.valueOf(instalments.size()),
                        "instalmentIds",
                        instalments.stream().map(i -> i.id().toString()).collect(Collectors.joining(",")))));
        return new Disbursement(id, applicationId, citizenId, journeyCode, STATUS_ISSUED, now, instalments);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Disbursement> forApplication(UUID applicationId) {
        return jdbc.query(
                        "SELECT id, application_id, citizen_id, journey_code, status, created_at"
                                + " FROM payments_disbursement WHERE application_id = ?",
                        (rs, i) -> new Disbursement(
                                rs.getObject("id", UUID.class),
                                rs.getObject("application_id", UUID.class),
                                rs.getObject("citizen_id", UUID.class),
                                rs.getString("journey_code"),
                                rs.getString("status"),
                                rs.getTimestamp("created_at").toInstant(),
                                List.of()),
                        applicationId)
                .stream()
                .findFirst()
                .map(d -> new Disbursement(
                        d.id(), d.applicationId(), d.citizenId(), d.journeyCode(), d.status(), d.createdAt(),
                        instalmentsOf(d.id())));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instalment> instalment(UUID instalmentId) {
        return jdbc.query(
                        "SELECT id, sequence_no, status FROM payments_instalment WHERE id = ?",
                        (rs, i) -> new Instalment(rs.getObject("id", UUID.class), rs.getInt("sequence_no"), rs.getString("status")),
                        instalmentId)
                .stream()
                .findFirst();
    }

    private List<Instalment> instalmentsOf(UUID disbursementId) {
        return jdbc.query(
                "SELECT id, sequence_no, status FROM payments_instalment WHERE disbursement_id = ? ORDER BY sequence_no",
                (rs, i) -> new Instalment(rs.getObject("id", UUID.class), rs.getInt("sequence_no"), rs.getString("status")),
                disbursementId);
    }
}
