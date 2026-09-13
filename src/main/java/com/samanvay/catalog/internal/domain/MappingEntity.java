package com.samanvay.catalog.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "catalog_mapping")
public class MappingEntity {
    @Id
    private String ref;
    @Column(name = "connector_ref")
    private String connectorRef;
    @JdbcTypeCode(SqlTypes.JSON)
    private String rules;

    public String getRef() {
        return ref;
    }

    public void setRef(String ref) {
        this.ref = ref;
    }

    public String getConnectorRef() {
        return connectorRef;
    }

    public void setConnectorRef(String connectorRef) {
        this.connectorRef = connectorRef;
    }

    public String getRules() {
        return rules;
    }

    public void setRules(String rules) {
        this.rules = rules;
    }
}
