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
}
