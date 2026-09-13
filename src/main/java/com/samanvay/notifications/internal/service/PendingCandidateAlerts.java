package com.samanvay.notifications.internal.service;

import com.samanvay.identity.api.CandidateRaised;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

@Component
class PendingCandidateAlerts {
    private final List<CandidateRaised> pending = new CopyOnWriteArrayList<>();

    void add(CandidateRaised event) {
        pending.add(event);
    }

    List<CandidateRaised> drain() {
        List<CandidateRaised> copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }
}
