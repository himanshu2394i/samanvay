package com.samanvay.catalog.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "catalog_purpose")
public class PurposeEntity {
    @Id
    private String code;
    private String text;
    @Column(name = "ref_uri")
    private String refUri;
    @Column(name = "category_type")
    private String categoryType;
    private String status;
    @Column(name = "requester_department")
    private String requesterDepartment;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.ARRAY)
    @Column(name = "data_categories", columnDefinition = "text[]")
    private String[] dataCategories;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.ARRAY)
    @Column(name = "data_types", columnDefinition = "text[]")
    private String[] dataTypes;
    @Column(name = "requester_rule")
    private String requesterRule;
    @Column(name = "max_duration_days")
    private Integer maxDurationDays;
    @Column(name = "duration_rule")
    private String durationRule;
    private String frequency;
    @Column(name = "label_en")
    private String labelEn;
    @Column(name = "label_mr")
    private String labelMr;
    @Column(name = "label_en_status")
    private String labelEnStatus;
    @Column(name = "label_mr_status")
    private String labelMrStatus;
    @Column(name = "separate_opt_in")
    private boolean separateOptIn;
    @Column(name = "created_at")
    private Instant createdAt;

    public String getCode() {
        return code;
    }

    public String getText() {
        return text;
    }

    public String getRefUri() {
        return refUri;
    }

    public String getCategoryType() {
        return categoryType;
    }

    public String getStatus() {
        return status;
    }

    public String getRequesterDepartment() {
        return requesterDepartment;
    }

    public String[] getDataCategories() {
        return dataCategories;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String[] getDataTypes() {
        return dataTypes;
    }

    public String getRequesterRule() {
        return requesterRule;
    }

    public Integer getMaxDurationDays() {
        return maxDurationDays;
    }

    public String getDurationRule() {
        return durationRule;
    }

    public String getFrequency() {
        return frequency;
    }

    public String getLabelEn() {
        return labelEn;
    }

    public String getLabelMr() {
        return labelMr;
    }

    public String getLabelEnStatus() {
        return labelEnStatus;
    }

    public String getLabelMrStatus() {
        return labelMrStatus;
    }

    public boolean isSeparateOptIn() {
        return separateOptIn;
    }
}
