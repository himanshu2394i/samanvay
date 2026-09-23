package com.samanvay.connector.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.connector.api.BatchIngestor;
import com.samanvay.connector.api.BatchResult;
import com.samanvay.connector.internal.protocol.MockSftpStore;
import com.samanvay.shared.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = SamanvayApplication.class)
class BatchIngestIdempotencyIT extends PostgresIntegrationTest {

    @Autowired
    BatchIngestor ingestor;

    @Autowired
    MockSftpStore store;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void sameChecksumIsIdempotentAndCanResume() {
        String ds = "municipal-sftp-mock";
        store.put(ds, "resume.csv", "propertyId,propertyRef\nA,1\nB,2\n,bad\n");
        BatchResult first = ingestor.ingest(ds);
        assertThat(first.processed()).isGreaterThan(0);
        BatchResult second = ingestor.ingest(ds);
        assertThat(second.processed()).isZero();
        Integer files = jdbc.queryForObject(
                "SELECT count(*) FROM connector_batch_file WHERE data_source_code = ? AND filename = 'resume.csv'",
                Integer.class,
                ds);
        assertThat(files).isEqualTo(1);
        Integer rejected = jdbc.queryForObject(
                "SELECT count(*) FROM connector_exception WHERE source_file = 'resume.csv'", Integer.class);
        assertThat(rejected).isGreaterThan(0);
    }
}
