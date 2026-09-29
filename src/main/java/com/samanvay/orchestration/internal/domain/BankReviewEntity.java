package com.samanvay.orchestration.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One officer review of a bank check that did not auto-accept. Never holds the
 * bank holder's name; only the masked account, the machine reason, the matcher
 * version, and (once uploaded) a hash of the passbook. The file path is live only
 * until a decision or the retention window.
 */
@Entity
@Table(name = "orchestration_bank_review")
public class BankReviewEntity {

    @Id
    private UUID id;

    @Column(name = "application_id")
    private String applicationId;

    @Column(name = "citizen_id")
    private UUID citizenId;

    @Column(name = "account_masked")
    private String accountMasked;

    @Column(name = "review_reason")
    private String reviewReason;

    @Column(name = "matcher_version")
    private String matcherVersion;

    private String status;

    @Column(name = "document_hash")
    private String documentHash;

    @Column(name = "document_path")
    private String documentPath;

    @Column(name = "document_uploaded_at")
    private Instant documentUploadedAt;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decision_reason")
    private String decisionReason;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected BankReviewEntity() {}

    public BankReviewEntity(UUID id, String applicationId, UUID citizenId, String accountMasked,
            String reviewReason, String matcherVersion, String status, Instant createdAt) {
        this.id = id;
        this.applicationId = applicationId;
        this.citizenId = citizenId;
        this.accountMasked = accountMasked;
        this.reviewReason = reviewReason;
        this.matcherVersion = matcherVersion;
        this.status = status;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getApplicationId() { return applicationId; }
    public UUID getCitizenId() { return citizenId; }
    public String getAccountMasked() { return accountMasked; }
    public String getReviewReason() { return reviewReason; }
    public String getMatcherVersion() { return matcherVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDocumentHash() { return documentHash; }
    public void setDocumentHash(String documentHash) { this.documentHash = documentHash; }
    public String getDocumentPath() { return documentPath; }
    public void setDocumentPath(String documentPath) { this.documentPath = documentPath; }
    public Instant getDocumentUploadedAt() { return documentUploadedAt; }
    public void setDocumentUploadedAt(Instant t) { this.documentUploadedAt = t; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }
    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
}
