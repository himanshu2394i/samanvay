package com.samanvay.orchestration.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orchestration_step_state")
public class StepStateEntity {
    @Id
    private UUID id;
    @Column(name = "instance_id")
    private UUID instanceId;
    @Column(name = "step_code")
    private String stepCode;
    private String status;
    @Column(name = "attempt_count")
    private int attemptCount;
    @Column(name = "last_failure_reason")
    private String lastFailureReason;
    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public void setInstanceId(UUID instanceId) {
        this.instanceId = instanceId;
    }

    public String getStepCode() {
        return stepCode;
    }

    public void setStepCode(String stepCode) {
        this.stepCode = stepCode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public void setLastFailureReason(String lastFailureReason) {
        this.lastFailureReason = lastFailureReason;
    }
}
