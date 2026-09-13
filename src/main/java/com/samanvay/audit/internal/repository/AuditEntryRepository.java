package com.samanvay.audit.internal.repository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditQuery;
import com.samanvay.audit.api.Outcome;
import com.samanvay.shared.CanonicalJson;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class AuditEntryRepository {

    private static final TypeReference<Map<String, Object>> META_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final CanonicalJson canonicalJson;
    private final ObjectMapper metaMapper = new ObjectMapper();
    private final RowMapper<AuditEntryRow> rowMapper = this::mapRow;

    AuditEntryRepository(JdbcTemplate jdbc, CanonicalJson canonicalJson) {
        this.jdbc = jdbc;
        this.canonicalJson = canonicalJson;
    }

    public Optional<byte[]> findLatestHash() {
        return jdbc.query(
                "SELECT hash FROM audit.audit_entry ORDER BY seq DESC LIMIT 1",
                rs -> rs.next() ? Optional.of(rs.getBytes("hash")) : Optional.empty());
    }

    public Optional<byte[]> findHashAt(long seq) {
        return jdbc.query(
                "SELECT hash FROM audit.audit_entry WHERE seq = ?",
                rs -> rs.next() ? Optional.of(rs.getBytes("hash")) : Optional.empty(),
                seq);
    }

    public long currentMaxSeq() {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(seq), 0) FROM audit.audit_entry", Long.class);
        return max == null ? 0 : max;
    }

    public long insert(AuditEntry entry, byte[] prevHash, byte[] hash) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(
                con -> {
                    var ps = con.prepareStatement(
                            """
                            INSERT INTO audit.audit_entry
                                (actor_type, actor_id, action, subject_id, resource, department_id,
                                 consent_id, grant_id, outcome, reason, meta, prev_hash, hash)
                            VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)
                            """,
                            new String[] {"seq"});
                    ps.setString(1, entry.actorType().name());
                    ps.setString(2, entry.actorId());
                    ps.setString(3, entry.action());
                    ps.setString(4, entry.subjectId());
                    ps.setString(5, entry.resource());
                    ps.setString(6, entry.departmentId());
                    ps.setObject(7, entry.consentId());
                    ps.setObject(8, entry.grantId());
                    ps.setString(9, entry.outcome().name());
                    ps.setString(10, entry.reason());
                    ps.setString(11, toJson(entry.meta()));
                    ps.setBytes(12, prevHash);
                    ps.setBytes(13, hash);
                    return ps;
                },
                keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<AuditEntryRow> findRange(long fromSeqInclusive, long toSeqInclusive) {
        return jdbc.query(
                """
                SELECT seq, ts, actor_type, actor_id, action, subject_id, resource, department_id,
                       consent_id, grant_id, outcome, reason, meta, prev_hash, hash
                FROM audit.audit_entry
                WHERE seq BETWEEN ? AND ?
                ORDER BY seq
                """,
                rowMapper,
                fromSeqInclusive,
                toSeqInclusive);
    }

    public Page<AuditEntryRow> search(AuditQuery query, Pageable page) {
        var sql = new StringBuilder(
                """
                SELECT seq, ts, actor_type, actor_id, action, subject_id, resource, department_id,
                       consent_id, grant_id, outcome, reason, meta, prev_hash, hash
                FROM audit.audit_entry
                WHERE 1=1
                """);
        var args = new ArrayList<Object>();
        if (query != null && query.subjectId() != null) {
            sql.append(" AND subject_id = ?");
            args.add(query.subjectId());
        }
        if (query != null && query.action() != null) {
            sql.append(" AND action = ?");
            args.add(query.action());
        }
        if (query != null && query.from() != null) {
            sql.append(" AND ts >= ?");
            args.add(Timestamp.from(query.from()));
        }
        if (query != null && query.to() != null) {
            sql.append(" AND ts <= ?");
            args.add(Timestamp.from(query.to()));
        }
        sql.append(" ORDER BY seq OFFSET ? LIMIT ?");
        args.add(page.getOffset());
        args.add(page.getPageSize());
        List<AuditEntryRow> rows = jdbc.query(sql.toString(), rowMapper, args.toArray());
        return new PageImpl<>(rows, page, rows.size());
    }

    private String toJson(Map<String, Object> meta) {
        return canonicalJson.serialize(meta == null ? Map.of() : meta);
    }

    private AuditEntryRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AuditEntryRow(
                rs.getLong("seq"),
                rs.getTimestamp("ts").toInstant(),
                ActorType.valueOf(rs.getString("actor_type")),
                rs.getString("actor_id"),
                rs.getString("action"),
                rs.getString("subject_id"),
                rs.getString("resource"),
                rs.getString("department_id"),
                rs.getObject("consent_id", UUID.class),
                rs.getObject("grant_id", UUID.class),
                Outcome.valueOf(rs.getString("outcome")),
                rs.getString("reason"),
                parseMeta(rs.getString("meta")),
                rs.getBytes("prev_hash"),
                rs.getBytes("hash"));
    }

    private Map<String, Object> parseMeta(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return metaMapper.readValue(json, META_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("audit meta must be valid json", e);
        }
    }
}
