package com.samanvay.registry.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "registry_discovery_grant")
@IdClass(DiscoveryGrantEntity.Key.class)
public class DiscoveryGrantEntity {
    @Id
    @Column(name = "subject_id")
    private UUID subjectId;
    @Id
    @Column(name = "requester_id")
    private String requesterId;
    @Id
    @Column(name = "data_category")
    private String dataCategory;
    @Column(name = "consent_id")
    private UUID consentId;
    @Column(name = "granted_at")
    private Instant grantedAt;

    public UUID getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(UUID subjectId) {
        this.subjectId = subjectId;
    }

    public String getRequesterId() {
        return requesterId;
    }

    public void setRequesterId(String requesterId) {
        this.requesterId = requesterId;
    }

    public String getDataCategory() {
        return dataCategory;
    }

    public void setDataCategory(String dataCategory) {
        this.dataCategory = dataCategory;
    }

    public UUID getConsentId() {
        return consentId;
    }

    public void setConsentId(UUID consentId) {
        this.consentId = consentId;
    }

    public void setGrantedAt(Instant grantedAt) {
        this.grantedAt = grantedAt;
    }

    public static class Key implements Serializable {
        public UUID subjectId;
        public String requesterId;
        public String dataCategory;
    }
}
