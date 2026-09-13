package com.samanvay.catalog.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "catalog_data_source")
public class DataSourceEntity {
    @Id
    private String code;
    @Column(name = "department_code")
    private String departmentCode;
    private String protocol;
    @Column(name = "base_host")
    private String baseHost;
    @Column(name = "auth_type")
    private String authType;
    @Column(name = "auth_config_ref")
    private String authConfigRef;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retry_config")
    private String retryConfig;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "breaker_config")
    private String breakerConfig;
    @Column(name = "health_status")
    private String healthStatus;
    @Column(name = "created_at")
    private Instant createdAt;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDepartmentCode() {
        return departmentCode;
    }

    public void setDepartmentCode(String departmentCode) {
        this.departmentCode = departmentCode;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getBaseHost() {
        return baseHost;
    }

    public void setBaseHost(String baseHost) {
        this.baseHost = baseHost;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public String getAuthConfigRef() {
        return authConfigRef;
    }

    public void setAuthConfigRef(String authConfigRef) {
        this.authConfigRef = authConfigRef;
    }

    public String getRetryConfig() {
        return retryConfig;
    }

    public void setRetryConfig(String retryConfig) {
        this.retryConfig = retryConfig;
    }

    public String getBreakerConfig() {
        return breakerConfig;
    }

    public void setBreakerConfig(String breakerConfig) {
        this.breakerConfig = breakerConfig;
    }

    public void setHealthStatus(String healthStatus) {
        this.healthStatus = healthStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
