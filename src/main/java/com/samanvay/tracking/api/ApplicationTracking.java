package com.samanvay.tracking.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ApplicationTracking {
    ApplicationView byReference(String referenceNo);

    /** The application for a journey instance id (the application id), if tracking has it. */
    Optional<ApplicationView> byInstanceId(UUID instanceId);

    Page<ApplicationSummary> forCitizen(UUID citizenId, Pageable p);

    Page<ApplicationSummary> recent(Pageable p);

    List<StepView> steps(String referenceNo);
}
