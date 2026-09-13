package com.samanvay.registry.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "registry_pointer")
public class PointerEntity {
    @Id
    private UUID id;
    @Column(name = "subject_id")
    private UUID subjectId;
    @Column(name = "subject_type")
    private String subjectType;
    @Column(name = "department_code")
    private String departmentCode;
    @Column(name = "data_category")
    private String dataCategory;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_ref")
    private String sourceRef;
    @Column(name = "issued_at")
    private LocalDate issuedAt;
    @Column(name = "valid_until")
    private LocalDate validUntil;
    @Column(name = "as_of")
    private Instant asOf;
    @Column(name = "freshness_mode")
    private String freshnessMode;
    private String status;
    @Column(name = "created_at")
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(UUID subjectId) {
        this.subjectId = subjectId;
    }

    public void setSubjectType(String subjectType) {
        this.subjectType = subjectType;
    }

    public String getDepartmentCode() {
        return departmentCode;
    }

    public void setDepartmentCode(String departmentCode) {
        this.departmentCode = departmentCode;
    }

    public String getDataCategory() {
        return dataCategory;
    }

    public void setDataCategory(String dataCategory) {
        this.dataCategory = dataCategory;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public LocalDate getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(LocalDate issuedAt) {
        this.issuedAt = issuedAt;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public void setValidUntil(LocalDate validUntil) {
        this.validUntil = validUntil;
    }

    public Instant getAsOf() {
        return asOf;
    }

    public void setAsOf(Instant asOf) {
        this.asOf = asOf;
    }

    public String getFreshnessMode() {
        return freshnessMode;
    }

    public void setFreshnessMode(String freshnessMode) {
        this.freshnessMode = freshnessMode;
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
}
