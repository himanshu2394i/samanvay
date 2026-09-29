package com.samanvay.connector.internal.source.jdbc;

import com.samanvay.connector.internal.source.SourceMode;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Real JDBC sources, keyed by data-source code, under {@code samanvay.sources.jdbc.sources.<code>}.
 * Credentials are NOT here: they come from SecretStore ({@code source-<code>-credential}, value
 * {@code username:password}, see SourceCredentials) in every mode, so the account the query runs as
 * is a read-only DB user separate from the migration owner.
 *
 * <p>Example:
 * <pre>
 * samanvay.sources.jdbc.sources.pollution-jdbc:
 *   mode: live
 *   jdbc-url: jdbc:postgresql://db.pollution.example:5432/pcb
 *   query-timeout: 5s
 *   max-rows: 1000
 * </pre>
 *
 * <p>No default points anywhere: a data source that is not the simulator host and has no entry here
 * is refused, not guessed. Only parameterized SELECT is ever executed (see JdbcSqlGuard).
 */
@ConfigurationProperties("samanvay.sources.jdbc")
public record JdbcSourceProperties(Map<String, Source> sources) {

    public JdbcSourceProperties {
        sources = sources == null ? Map.of() : Map.copyOf(sources);
    }

    /**
     * @param mode required (sandbox|simulator|live); LIVE requires the credential at boot
     * @param jdbcUrl required JDBC URL (host/port/database); the account comes from SecretStore
     * @param queryTimeout per-query timeout (default 5s)
     * @param maxRows upper bound on rows the statement returns (default 1000)
     */
    public record Source(SourceMode mode, String jdbcUrl, Duration queryTimeout, Integer maxRows) {

        public Source {
            if (mode == null) {
                throw new IllegalArgumentException("samanvay.sources.jdbc.sources.<code>.mode is required (sandbox|simulator|live)");
            }
            if (jdbcUrl == null || jdbcUrl.isBlank()) {
                throw new IllegalArgumentException("samanvay.sources.jdbc.sources.<code>.jdbc-url is required");
            }
            queryTimeout = queryTimeout == null ? Duration.ofSeconds(5) : queryTimeout;
            maxRows = maxRows == null ? 1000 : maxRows;
        }
    }
}
