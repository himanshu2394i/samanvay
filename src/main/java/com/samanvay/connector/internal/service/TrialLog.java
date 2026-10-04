package com.samanvay.connector.internal.service;

import com.samanvay.connector.api.TrialHistory;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** The last trial per connector, kept in memory. ponytail: lost on restart and per instance; persist in a connector table if it must survive. */
@Service
public class TrialLog implements TrialHistory {

    private final Map<String, Trial> last = new ConcurrentHashMap<>();

    public void record(String connectorRef, String outcome) {
        last.put(connectorRef, new Trial(Instant.now(), outcome));
    }

    @Override
    public Optional<Trial> last(String connectorRef) {
        return Optional.ofNullable(last.get(connectorRef));
    }
}
