package com.samanvay.registry.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "registry_category_policy")
public class CategoryPolicyEntity {
    @Id
    @Column(name = "data_category")
    private String dataCategory;
    private String sensitivity;
    @Column(name = "discovery_policy")
    private String discoveryPolicy;

    public String getDataCategory() {
        return dataCategory;
    }

    public String getSensitivity() {
        return sensitivity;
    }

    public String getDiscoveryPolicy() {
        return discoveryPolicy;
    }
}
