package com.samanvay.orchestration.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "orchestration_instance")
public class InstanceEntity {
    @Id
    private UUID id;
    @Column(name = "journey_code")
    private String journeyCode;
    @Column(name = "citizen_id")
    private UUID citizenId;
    @Column(name = "process_instance_id")
    private String processInstanceId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pinned_connector_versions")
    private String pinnedConnectorVersions;
    @Column(name = "created_at")
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getJourneyCode() {
        return journeyCode;
    }

    public void setJourneyCode(String journeyCode) {
        this.journeyCode = journeyCode;
    }

    public UUID getCitizenId() {
        return citizenId;
    }

    public void setCitizenId(UUID citizenId) {
        this.citizenId = citizenId;
    }

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public void setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
    }

    public void setPinnedConnectorVersions(String pinnedConnectorVersions) {
        this.pinnedConnectorVersions = pinnedConnectorVersions;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
