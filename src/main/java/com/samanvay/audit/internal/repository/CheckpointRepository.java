package com.samanvay.audit.internal.repository;

import com.samanvay.audit.api.Checkpoint;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class CheckpointRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<Checkpoint> mapper = (rs, rowNum) -> new Checkpoint(
            rs.getLong("seq"),
            rs.getLong("upto_entry_seq"),
            rs.getBytes("root_hash"),
            rs.getTimestamp("signed_at").toInstant(),
            rs.getBytes("signature"),
            rs.getString("published_ref"),
            rs.getString("key_id"));

    CheckpointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Records which signing key id produced the signature, so a later rotation cannot orphan it. */
    public void insert(long uptoEntrySeq, byte[] rootHash, byte[] signature, String keyId) {
        jdbc.update(
                """
                INSERT INTO audit.audit_checkpoint (upto_entry_seq, root_hash, signature, key_id)
                VALUES (?, ?, ?, ?)
                """,
                uptoEntrySeq,
                rootHash,
                signature,
                keyId);
    }

    public Optional<Checkpoint> findLatest() {
        return jdbc.query(
                """
                SELECT seq, upto_entry_seq, root_hash, signed_at, signature, published_ref, key_id
                FROM audit.audit_checkpoint
                ORDER BY seq DESC
                LIMIT 1
                """,
                rs -> rs.next() ? Optional.of(mapper.mapRow(rs, 0)) : Optional.empty());
    }
}
