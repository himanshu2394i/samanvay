package com.samanvay.registry.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;

@Entity
@Table(name = "registry_clearance")
@IdClass(ClearanceEntity.Key.class)
public class ClearanceEntity {
    @Id
    @Column(name = "requester_id")
    private String requesterId;
    @Id
    private String sensitivity;

    public String getRequesterId() {
        return requesterId;
    }

    public String getSensitivity() {
        return sensitivity;
    }

    public static class Key implements Serializable {
        public String requesterId;
        public String sensitivity;
    }
}
