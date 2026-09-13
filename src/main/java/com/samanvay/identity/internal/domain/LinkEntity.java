package com.samanvay.identity.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "identity_link")
public class LinkEntity {
    @Id
    private UUID id;
    @Column(name = "citizen_id")
    private UUID citizenId;
    @Column(name = "department_code")
    private String departmentCode;
    @Column(name = "local_id_type")
    private String localIdType;
    @Column(name = "local_id_token")
    private String localIdToken;
    private String provenance;
    private BigDecimal confidence;
    private String status;
    @Column(name = "verified_at")
    private Instant verifiedAt;
    @Column(name = "created_at")
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getCitizenId() {
        return citizenId;
    }

    public void setCitizenId(UUID citizenId) {
        this.citizenId = citizenId;
    }

    public String getDepartmentCode() {
        return departmentCode;
    }

    public void setDepartmentCode(String departmentCode) {
        this.departmentCode = departmentCode;
    }

    public String getLocalIdType() {
        return localIdType;
    }

    public void setLocalIdType(String localIdType) {
        this.localIdType = localIdType;
    }

    public String getLocalIdToken() {
        return localIdToken;
    }

    public void setLocalIdToken(String localIdToken) {
        this.localIdToken = localIdToken;
    }

    public String getProvenance() {
        return provenance;
    }

    public void setProvenance(String provenance) {
        this.provenance = provenance;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setVerifiedAt(Instant verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
