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

    protected SchemaEntity() {}

    public static SchemaEntity of(String ref, String name, int version, String definition) {
        SchemaEntity e = new SchemaEntity();
        e.ref = ref;
        e.name = name;
        e.version = version;
        e.definition = definition;
        return e;
    }

    public String getRef() {
        return ref;
    }

    public String getName() {
        return name;
    }

    public int getVersion() {
        return version;
    }

    public String getDefinition() {
        return definition;
    }
}
