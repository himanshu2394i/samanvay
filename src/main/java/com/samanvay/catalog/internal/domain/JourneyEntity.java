package com.samanvay.catalog.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "catalog_journey")
public class JourneyEntity {
    @Id
    private String code;
    private String name;
    @Column(name = "bpmn_ref")
    private String bpmnRef;
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "required_categories", columnDefinition = "text[]")
    private String[] requiredCategories;
    @JdbcTypeCode(SqlTypes.JSON)
    private String policy;
    private String status;
    @Column(name = "academic_year_start_month")
    private Integer academicYearStartMonth;

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getBpmnRef() {
        return bpmnRef;
    }

    public String[] getRequiredCategories() {
        return requiredCategories;
    }

    public String getPolicy() {
        return policy;
    }

    public String getStatus() {
        return status;
    }

    public Integer getAcademicYearStartMonth() {
        return academicYearStartMonth;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setBpmnRef(String bpmnRef) {
        this.bpmnRef = bpmnRef;
    }

    public void setRequiredCategories(String[] requiredCategories) {
        this.requiredCategories = requiredCategories;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setAcademicYearStartMonth(Integer academicYearStartMonth) {
        this.academicYearStartMonth = academicYearStartMonth;
    }
}
