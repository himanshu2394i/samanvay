package com.samanvay.orchestration.internal.service;

import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.orchestration.api.ApplicationNotApprovableException;
import com.samanvay.orchestration.api.ApplicationNotRejectableException;
import com.samanvay.orchestration.api.ApplicationStateChanged;
import com.samanvay.orchestration.api.InstanceNotFoundException;
import com.samanvay.orchestration.internal.domain.InstanceEntity;
import com.samanvay.orchestration.internal.repository.InstanceRepository;
import com.samanvay.shared.InvalidRequestException;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The officer approval step: a VERIFIED application becomes APPROVED, terminal. Approval publishes
 * {@link ApplicationStateChanged}{@code (instanceId, "APPROVED")}; whoever cares (tracking, payments'
 * disbursement, notifications) listens - this module never calls them.
 *
 * <p>Only VERIFIED is approvable. PARTIALLY_VERIFIED still has a source pending (an open exception),
 * so approving it would pay out on incomplete evidence; REJECTED and CLOSED are terminal already.
 *
 * <p>Idempotent and race-safe: the transition is one conditional UPDATE, so of two concurrent
 * approvals exactly one wins and publishes the event and the audit entry; the other (and any later
 * repeat) sees APPROVED and is a no-op, so nothing is disbursed twice.
 */
@Service
public class ApplicationApprovalService {

    static final String VERIFIED = "VERIFIED";
    static final String APPROVED = "APPROVED";
    static final String ACTION = "APPLICATION_APPROVED";
    static final String REJECTED = "REJECTED";
    static final String REJECT_ACTION = "APPLICATION_REJECTED";

    private final InstanceRepository instances;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    ApplicationApprovalService(
            InstanceRepository instances, JdbcTemplate jdbc, AuditService audit, ApplicationEventPublisher events) {
        this.instances = instances;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    /**
     * @param officer the approving officer's token subject
     * @return the application's status after the call: always {@code APPROVED}
     * @throws InstanceNotFoundException no such application
     * @throws ApplicationNotApprovableException the application is not VERIFIED (and not already APPROVED)
     */
    @Transactional
    public String approve(UUID instanceId, String officer) {
        InstanceEntity instance = instances.findById(instanceId).orElseThrow(InstanceNotFoundException::new);
        int moved = jdbc.update(
                "UPDATE orchestration_instance SET status = ? WHERE id = ? AND status = ?",
                APPROVED,
                instanceId,
                VERIFIED);
        if (moved == 0) {
            String current = jdbc.queryForObject(
                    "SELECT status FROM orchestration_instance WHERE id = ?", String.class, instanceId);
            if (APPROVED.equals(current)) {
                return APPROVED; // already approved: no second event, no second audit row
            }
            throw new ApplicationNotApprovableException(instanceId, current);
        }
        audit.record(new AuditEntry(
                ActorType.OFFICER,
                officer,
                ACTION,
                instance.getCitizenId().toString(),
                "application",
                null,
                null,
                null,
                Outcome.ALLOWED,
                null,
                Map.of("applicationId", instanceId.toString(), "journeyCode", instance.getJourneyCode())));
        events.publishEvent(new ApplicationStateChanged(instanceId, APPROVED));
        return APPROVED;
    }

    /**
     * The officer's other disposition: a non-terminal application becomes REJECTED, terminal. Publishes
     * {@link ApplicationStateChanged}{@code (instanceId, "REJECTED")}; {@code DisbursementOnApproval}
     * acts only on APPROVED, so a rejection never disburses. The {@code reason} is recorded in the audit
     * entry's metadata (no schema change).
     *
     * <p>Rejectable from any non-terminal status (SUBMITTED, PARTIALLY_VERIFIED, VERIFIED); the terminal
     * states (APPROVED, REJECTED, CLOSED) are not. Idempotent and race-safe like {@link #approve}: one
     * conditional UPDATE, so a repeat sees REJECTED and is a no-op (no second event or audit row).
     *
     * @param officer the rejecting officer's token subject
     * @param reason required, non-blank; recorded in the audit metadata
     * @return the application's status after the call: always {@code REJECTED}
     * @throws InvalidRequestException the reason is blank (400)
     * @throws InstanceNotFoundException no such application
     * @throws ApplicationNotRejectableException the application is already terminal
     */
    @Transactional
    public String reject(UUID instanceId, String officer, String reason) {
        String cleanReason = InvalidRequestException.requireText(reason, "reason");
        InstanceEntity instance = instances.findById(instanceId).orElseThrow(InstanceNotFoundException::new);
        int moved = jdbc.update(
                "UPDATE orchestration_instance SET status = ? WHERE id = ? AND status NOT IN ('APPROVED','REJECTED','CLOSED')",
                REJECTED,
                instanceId);
        if (moved == 0) {
            String current = jdbc.queryForObject(
                    "SELECT status FROM orchestration_instance WHERE id = ?", String.class, instanceId);
            if (REJECTED.equals(current)) {
                return REJECTED; // already rejected: no second event, no second audit row
            }
            throw new ApplicationNotRejectableException(instanceId, current);
        }
        // A rejected application is final: its queue entries cannot be worked any more.
        jdbc.update("UPDATE orchestration_exception SET status = 'RESOLVED' WHERE instance_id = ? AND status = 'OPEN'", instanceId);
        audit.record(new AuditEntry(
                ActorType.OFFICER,
                officer,
                REJECT_ACTION,
                instance.getCitizenId().toString(),
                "application",
                null,
                null,
                null,
                Outcome.ALLOWED,
                null,
                Map.of(
                        "applicationId", instanceId.toString(),
                        "journeyCode", instance.getJourneyCode(),
                        "reason", cleanReason)));
        events.publishEvent(new ApplicationStateChanged(instanceId, REJECTED));
        return REJECTED;
    }
}
