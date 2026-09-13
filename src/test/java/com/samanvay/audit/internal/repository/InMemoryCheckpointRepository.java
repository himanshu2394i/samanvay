package com.samanvay.audit.internal.repository;

import com.samanvay.audit.api.Checkpoint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

class InMemoryCheckpointRepository extends CheckpointRepository {

    final List<Checkpoint> rows = new ArrayList<>();

    InMemoryCheckpointRepository() {
        super(null);
    }

    @Override
    void insert(long uptoEntrySeq, byte[] rootHash, byte[] signature) {
        rows.add(new Checkpoint(rows.size() + 1L, uptoEntrySeq, rootHash, Instant.now(), signature, null));
    }

    @Override
    Optional<Checkpoint> findLatest() {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getLast());
    }
}
