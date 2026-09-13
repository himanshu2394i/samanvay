package com.samanvay.notifications.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_delivery")
public class DeliveryEntity {
    @Id
    private UUID id;
    @Column(name = "recipient_id")
    private String recipientId;
    @Column(name = "event_type")
    private String eventType;
    private String channel;
    @Column(name = "dedupe_key")
    private String dedupeKey;
    @Column(name = "template_ref")
    private String templateRef;
    @Column(name = "rendered_body")
    private String renderedBody;
    private String status;
    private int attempts;
    @Column(name = "last_error")
    private String lastError;
    @Column(name = "sent_at")
    private Instant sentAt;
    @Column(name = "created_at")
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getRecipientId() {
        return recipientId;
    }

    public void setRecipientId(String recipientId) {
        this.recipientId = recipientId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public void setDedupeKey(String dedupeKey) {
        this.dedupeKey = dedupeKey;
    }

    public void setTemplateRef(String templateRef) {
        this.templateRef = templateRef;
    }

    public String getRenderedBody() {
        return renderedBody;
    }

    public void setRenderedBody(String renderedBody) {
        this.renderedBody = renderedBody;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
