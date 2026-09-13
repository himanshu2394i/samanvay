package com.samanvay.tracking.api;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ApplicationTracking {
    ApplicationView byReference(String referenceNo);

    Page<ApplicationSummary> forCitizen(UUID citizenId, Pageable p);

    List<StepView> steps(String referenceNo);
}
