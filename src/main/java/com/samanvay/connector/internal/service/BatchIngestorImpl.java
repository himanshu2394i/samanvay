package com.samanvay.connector.internal.service;

import com.samanvay.connector.api.BatchIngestCompleted;
import com.samanvay.connector.api.BatchIngestor;
import com.samanvay.connector.api.BatchResult;
import com.samanvay.shared.BatchRowRejected;
import com.samanvay.connector.internal.protocol.MockSftpStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BatchIngestorImpl implements BatchIngestor {

    private final MockSftpStore store;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    BatchIngestorImpl(MockSftpStore store, JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.store = store;
        this.jdbc = jdbc;
        this.events = events;
    }

    @Override
    @Transactional
    public BatchResult ingest(String dataSourceCode) {
        List<MockSftpStore.File> files = store.list(dataSourceCode);
        int processed = 0;
        int rejected = 0;
        int offset = 0;
        boolean complete = true;
        for (MockSftpStore.File file : files) {
            BatchFileState state = openOrResume(dataSourceCode, file);
            if ("COMPLETE".equals(state.status())) {
                continue;
            }
            String[] lines = file.csv().split("\\R");
            int start = Math.max(1, state.rowOffset() + 1);
            for (int i = start; i < lines.length; i++) {
                String line = lines[i];
                String[] cols = line == null ? new String[0] : line.split(",", -1);
                if (line == null || line.isBlank() || cols.length < 2 || cols[0].isBlank()) {
                    rejected++;
                    jdbc.update(
                            """
                            INSERT INTO connector_exception
                              (id, data_source_code, connector_ref, source_file, raw_content, violations, status)
                            VALUES (?, ?, null, ?, ?, '["malformed"]'::jsonb, 'OPEN')
                            """,
                            UUID.randomUUID(),
                            dataSourceCode,
                            file.filename(),
                            String.valueOf(line));
                    events.publishEvent(new BatchRowRejected(dataSourceCode, file.filename(), i, "malformed"));
                } else {
                    processed++;
                }
                offset = i;
                jdbc.update(
                        "UPDATE connector_batch_file SET row_offset = ? WHERE id = ?",
                        offset,
                        state.id());
            }
            jdbc.update(
                    "UPDATE connector_batch_file SET status = 'COMPLETE', rows_total = ?, completed_at = ? WHERE id = ?",
                    Math.max(0, lines.length - 1),
                    Timestamp.from(Instant.now()),
                    state.id());
            events.publishEvent(new BatchIngestCompleted(dataSourceCode, file.filename(), processed, rejected));
        }
        return new BatchResult(processed, rejected, complete, offset);
    }

    private BatchFileState openOrResume(String dataSourceCode, MockSftpStore.File file) {
        List<BatchFileState> existing = jdbc.query(
                """
                SELECT id, row_offset, status FROM connector_batch_file
                WHERE data_source_code = ? AND checksum = ?
                """,
                (rs, n) -> new BatchFileState(
                        rs.getObject("id", UUID.class), rs.getInt("row_offset"), rs.getString("status")),
                dataSourceCode,
                file.checksum());
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO connector_batch_file
                  (id, data_source_code, filename, checksum, file_timestamp, row_offset, status)
                VALUES (?, ?, ?, ?, now(), 0, 'IN_PROGRESS')
                """,
                id,
                dataSourceCode,
                file.filename(),
                file.checksum());
        return new BatchFileState(id, 0, "IN_PROGRESS");
    }

    private record BatchFileState(UUID id, int rowOffset, String status) {}
}
