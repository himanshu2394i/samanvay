package com.samanvay.consent.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "consent_access_grant")
public class AccessGrantEntity {
    @Id
    private UUID id;
    private byte[] nonce;
    @Column(name = "consent_id")
    private UUID consentId;
    @Column(name = "consent_version")
    private int consentVersion;
    @Column(name = "subject_citizen_id")
    private UUID subjectCitizenId;
    @Column(name = "requester_id")
    private String requesterId;
    @Column(name = "data_category")
    private String dataCategory;
    @Column(name = "department_id")
    private String departmentId;
    @Column(name = "connector_ref")
    private String connectorRef;
    @Column(name = "purpose_code")
    private String purposeCode;
    @Column(name = "issued_at")
    private Instant issuedAt;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "used_at")
    private Instant usedAt;
    private byte[] signature;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public byte[] getNonce() {
        return nonce;
    }

    public void setNonce(byte[] nonce) {
        this.nonce = nonce;
    }

    public UUID getConsentId() {
        return consentId;
    }

    public void setConsentId(UUID consentId) {
        this.consentId = consentId;
    }

    public int getConsentVersion() {
        return consentVersion;
    }

    public void setConsentVersion(int consentVersion) {
        this.consentVersion = consentVersion;
    }

    public void setSubjectCitizenId(UUID subjectCitizenId) {
        this.subjectCitizenId = subjectCitizenId;
    }

    public void setRequesterId(String requesterId) {
        this.requesterId = requesterId;
    }

    public void setDataCategory(String dataCategory) {
        this.dataCategory = dataCategory;
    }

    public void setDepartmentId(String departmentId) {
        this.departmentId = departmentId;
    }

    public void setConnectorRef(String connectorRef) {
        this.connectorRef = connectorRef;
    }

    public void setPurposeCode(String purposeCode) {
        this.purposeCode = purposeCode;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(Instant usedAt) {
        this.usedAt = usedAt;
    }

    public void setSignature(byte[] signature) {
        this.signature = signature;
    }
}
