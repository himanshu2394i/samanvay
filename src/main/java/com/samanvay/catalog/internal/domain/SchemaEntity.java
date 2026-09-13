package com.samanvay.catalog.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "catalog_schema")
public class SchemaEntity {
    @Id
    private String ref;
    private String name;
    private int version;
    @JdbcTypeCode(SqlTypes.JSON)
    private String definition;

    public String getRef() {
        return ref;
    }

    public String getDefinition() {
        return definition;
    }
}
