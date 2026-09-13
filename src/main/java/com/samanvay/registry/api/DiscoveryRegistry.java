package com.samanvay.registry.api;

import com.samanvay.shared.DataCategory;
import com.samanvay.shared.RequesterRef;
import com.samanvay.shared.SubjectRef;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface DiscoveryRegistry {
    List<Pointer> discover(SubjectRef subject, RequesterRef requester, Set<DataCategory> categories);

    Optional<Pointer> locate(SubjectRef subject, String departmentCode, DataCategory category, RequesterRef requester);

    void upsert(PointerUpsert p);

    void withdraw(UUID pointerId, String reason);

    void allowDiscovery(UUID subjectId, String requesterId, String category, UUID consentId);

    void revokeDiscovery(UUID consentId);

    boolean hasClearance(RequesterRef requester, Sensitivity sensitivity);
}
