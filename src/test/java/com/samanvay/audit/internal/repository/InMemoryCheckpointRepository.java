package com.samanvay.audit.internal.repository;

import com.samanvay.audit.api.Checkpoint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class InMemoryCheckpointRepository extends CheckpointRepository {

    final List<Checkpoint> rows = new ArrayList<>();

    public InMemoryCheckpointRepository() {
        super(null);
    }

    @Override
    public void insert(long uptoEntrySeq, byte[] rootHash, byte[] signature) {
        rows.add(new Checkpoint(rows.size() + 1L, uptoEntrySeq, rootHash, Instant.now(), signature, null));
    }

    @Override
    public Optional<Checkpoint> findLatest() {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getLast());
    }
}
