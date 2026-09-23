package com.samanvay.catalog.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "catalog_connector")
public class ConnectorEntity {
    @Id
    private String ref;
    @Column(name = "connector_id")
    private String connectorId;
    private int version;
    @Column(name = "data_source_code")
    private String dataSourceCode;
    @Column(name = "data_category")
    private String dataCategory;
    @JdbcTypeCode(SqlTypes.JSON)
    private String capabilities;
    @JdbcTypeCode(SqlTypes.JSON)
    private String inputs;
    @Column(name = "sla_ms")
    private Integer slaMs;
    private String status;
    @Column(name = "created_at")
    private Instant createdAt;

    public String getRef() {
        return ref;
    }

    public void setRef(String ref) {
        this.ref = ref;
    }

    public String getConnectorId() {
        return connectorId;
    }

    public void setConnectorId(String connectorId) {
        this.connectorId = connectorId;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getDataSourceCode() {
        return dataSourceCode;
    }

    public void setDataSourceCode(String dataSourceCode) {
        this.dataSourceCode = dataSourceCode;
    }

    public String getDataCategory() {
        return dataCategory;
    }

    public void setDataCategory(String dataCategory) {
        this.dataCategory = dataCategory;
    }

    public String getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(String capabilities) {
        this.capabilities = capabilities;
    }

    public String getInputs() {
        return inputs;
    }

    public void setInputs(String inputs) {
        this.inputs = inputs;
    }

    public Integer getSlaMs() {
        return slaMs;
    }

    public void setSlaMs(Integer slaMs) {
        this.slaMs = slaMs;
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
