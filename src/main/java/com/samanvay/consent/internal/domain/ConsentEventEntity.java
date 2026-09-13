package com.samanvay.consent.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "consent_event")
public class ConsentEventEntity {
    @Id
    private UUID id;
    @Column(name = "consent_id")
    private UUID consentId;
    @Column(name = "event_type")
    private String eventType;
    @Column(name = "occurred_at")
    private Instant occurredAt;
    @JdbcTypeCode(SqlTypes.JSON)
    private String detail;

    public void setId(UUID id) {
        this.id = id;
    }

    public void setConsentId(UUID consentId) {
        this.consentId = consentId;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
