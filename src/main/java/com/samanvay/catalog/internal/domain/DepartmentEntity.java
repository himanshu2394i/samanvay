package com.samanvay.catalog.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "catalog_department")
public class DepartmentEntity {
    @Id
    private String code;
    private String name;
    @Column(name = "idp_realm")
    private String idpRealm;
    @Column(name = "contact_email")
    private String contactEmail;
    @Column(name = "default_sla_ms")
    private Integer defaultSlaMs;
    private String status;
    @Column(name = "created_at")
    private Instant createdAt;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public void setIdpRealm(String idpRealm) {
        this.idpRealm = idpRealm;
    }

    public void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail;
    }

    public void setDefaultSlaMs(Integer defaultSlaMs) {
        this.defaultSlaMs = defaultSlaMs;
    }
}
