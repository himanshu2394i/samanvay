package com.samanvay.consent.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "consent_request")
public class ConsentRequestEntity {
    @Id
    private UUID id;
    @Column(name = "subject_citizen_id")
    private UUID subjectCitizenId;
    @Column(name = "requester_id")
    private String requesterId;
    @Column(name = "purpose_code")
    private String purposeCode;
    @Column(name = "purpose_text")
    private String purposeText;
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "data_categories", columnDefinition = "text[]")
    private String[] dataCategories;
    private String status;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "responded_at")
    private Instant respondedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getSubjectCitizenId() {
        return subjectCitizenId;
    }

    public void setSubjectCitizenId(UUID subjectCitizenId) {
        this.subjectCitizenId = subjectCitizenId;
    }

    public String getRequesterId() {
        return requesterId;
    }

    public void setRequesterId(String requesterId) {
        this.requesterId = requesterId;
    }

    public String getPurposeCode() {
        return purposeCode;
    }

    public void setPurposeCode(String purposeCode) {
        this.purposeCode = purposeCode;
    }

    public String getPurposeText() {
        return purposeText;
    }

    public void setPurposeText(String purposeText) {
        this.purposeText = purposeText;
    }

    public String[] getDataCategories() {
        return dataCategories;
    }

    public void setDataCategories(String[] dataCategories) {
        this.dataCategories = dataCategories;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public void setRespondedAt(Instant respondedAt) {
        this.respondedAt = respondedAt;
    }
}
