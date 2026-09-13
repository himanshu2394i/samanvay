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
@Table(name = "consent_artifact")
public class ConsentArtifactEntity {
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
    private String granularity;
    @Column(name = "valid_from")
    private Instant validFrom;
    @Column(name = "valid_until")
    private Instant validUntil;
    @Column(name = "frequency_limit")
    private Integer frequencyLimit;
    private String status;
    private int version;
    @Column(name = "citizen_auth_ref")
    private String citizenAuthRef;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

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

    public String getGranularity() {
        return granularity;
    }

    public void setGranularity(String granularity) {
        this.granularity = granularity;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(Instant validFrom) {
        this.validFrom = validFrom;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public void setValidUntil(Instant validUntil) {
        this.validUntil = validUntil;
    }

    public Integer getFrequencyLimit() {
        return frequencyLimit;
    }

    public void setFrequencyLimit(Integer frequencyLimit) {
        this.frequencyLimit = frequencyLimit;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getCitizenAuthRef() {
        return citizenAuthRef;
    }

    public void setCitizenAuthRef(String citizenAuthRef) {
        this.citizenAuthRef = citizenAuthRef;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
