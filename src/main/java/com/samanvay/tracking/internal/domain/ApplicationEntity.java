package com.samanvay.tracking.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tracking_application")
public class ApplicationEntity {
    @Id
    private UUID id;
    @Column(name = "reference_no")
    private String referenceNo;
    @Column(name = "citizen_id")
    private UUID citizenId;
    @Column(name = "journey_code")
    private String journeyCode;
    @Column(name = "process_instance_id")
    private String processInstanceId;
    private String status;
    @Column(name = "submitted_at")
    private Instant submittedAt;
    @Column(name = "sla_due_at")
    private Instant slaDueAt;
    @Column(name = "closed_at")
    private Instant closedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getReferenceNo() {
        return referenceNo;
    }

    public void setReferenceNo(String referenceNo) {
        this.referenceNo = referenceNo;
    }

    public UUID getCitizenId() {
        return citizenId;
    }

    public void setCitizenId(UUID citizenId) {
        this.citizenId = citizenId;
    }

    public String getJourneyCode() {
        return journeyCode;
    }

    public void setJourneyCode(String journeyCode) {
        this.journeyCode = journeyCode;
    }

    public void setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Instant getSlaDueAt() {
        return slaDueAt;
    }

    public void setSlaDueAt(Instant slaDueAt) {
        this.slaDueAt = slaDueAt;
    }
}
