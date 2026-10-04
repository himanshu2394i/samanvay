package com.samanvay.orchestration.internal.service;

import com.samanvay.identity.api.CitizenActivity;
import com.samanvay.orchestration.internal.repository.InstanceRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** A citizen who has started a journey is never folded into another record. */
@Component
class OrchestrationCitizenActivity implements CitizenActivity {

    private final InstanceRepository instances;

    OrchestrationCitizenActivity(InstanceRepository instances) {
        this.instances = instances;
    }

    @Override
    public boolean hasActivity(UUID citizenId) {
        return instances.existsByCitizenId(citizenId);
    }
}
