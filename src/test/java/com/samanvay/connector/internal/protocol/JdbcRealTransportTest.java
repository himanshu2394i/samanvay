package com.samanvay.connector.internal.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.AdapterResponse;
import com.samanvay.connector.api.IllegalConnectorConfigurationException;
import com.samanvay.connector.internal.source.SourceCredentials;
import com.samanvay.connector.internal.source.SourceMode;
import com.samanvay.connector.internal.source.jdbc.JdbcQueryClient;
import com.samanvay.connector.internal.source.jdbc.JdbcSourceProperties;
import com.samanvay.connector.internal.source.jdbc.JdbcTransportException;
import com.samanvay.shared.SecretStore;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Real JDBC transport, end to end against an in-process H2 database (a real JDBC server, no
 * Docker) with a dedicated read-only user, mirroring {@link SftpCsvRealTransportTest}. Proves the
 * parameterized SELECT is bound (not string-substituted), credentials come from SecretStore, and
 * the simulator host still reads the in-memory backend.
 */
class JdbcRealTransportTest {

    static final String SOURCE = "pollution-jdbc-real";
    static final String USER = "app_ro";
    static final String PASSWORD = "app-secret-Zx81";
    static final String SQL = "SELECT clearance_status, holder FROM pcb_clearance WHERE premise_id = :premiseId";

    String url;
    Connection keepAlive;

    @BeforeEach
    void startDb() throws Exception {
        // Unique in-memory DB per test. Only the admin keep-alive connection carries DB_CLOSE_DELAY=-1
        // (a setting a non-admin user may not apply); the source connects with the bare URL.
        url = "jdbc:h2:mem:dept_" + UUID.randomUUID().toString().replace("-", "");
        keepAlive = DriverManager.getConnection(url + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement st = keepAlive.createStatement()) {
            st.execute("CREATE TABLE pcb_clearance (premise_id VARCHAR(64) PRIMARY KEY, clearance_status VARCHAR(32), holder VARCHAR(64))");
            st.execute("INSERT INTO pcb_clearance VALUES ('PR-1', 'clear', 'Acme Textiles')");
            st.execute("INSERT INTO pcb_clearance VALUES ('PR-2', 'pending', 'Beta Dyes')");
            st.execute("CREATE USER " + USER + " PASSWORD '" + PASSWORD + "'");
            st.execute("GRANT SELECT ON pcb_clearance TO " + USER);
        }
    }

    @AfterEach
    void stopDb() throws Exception {
        if (keepAlive != null) {
            keepAlive.close();
        }
    }

    /** Holds one credential; refuses resolve() (which may generate), like real source credentials. */
    record FixedSecrets(Map<String, String> values) implements SecretStore {
        @Override
        public Secret resolve(String key) {
            throw new AssertionError("source credentials must use find()");
        }

        @Override
        public Optional<Secret> find(String key) {
            return Optional.ofNullable(values.get(key)).map(v -> new Secret(v.getBytes(StandardCharsets.UTF_8)));
        }
    }

    JdbcSourceProperties.Source source() {
        return new JdbcSourceProperties.Source(SourceMode.LIVE, url, Duration.ofSeconds(5), 1000);
    }

    JdbcAdapter adapter(String credential) {
        Map<String, String> secrets = new HashMap<>();
        if (credential != null) {
            secrets.put(SourceCredentials.secretKey(SOURCE), credential);
        }
        var client = new JdbcQueryClient(
                new JdbcSourceProperties(Map.of(SOURCE, source())), new SourceCredentials(new FixedSecrets(secrets)));
        return new JdbcAdapter(new MockDepartmentBackend(), client);
    }

    static AdapterRequest request(String host, String premiseId) {
        return new AdapterRequest(SOURCE, "JDBC", host, "/pollution", SQL, Map.of("premiseId", premiseId), "secret:none");
    }

    @Test
    void queries_over_real_jdbc_and_returns_the_matching_row() {
        AdapterResponse response = adapter(USER + ":" + PASSWORD).execute(request("db.invalid", "PR-1"));

        assertThat(response.body().get("clearance_status").asString()).isEqualTo("clear");
        assertThat(response.body().get("holder").asString()).isEqualTo("Acme Textiles");
    }

    @Test
    void unknown_id_returns_an_empty_body_not_another_rows_data() {
        AdapterResponse response = adapter(USER + ":" + PASSWORD).execute(request("db.invalid", "PR-404"));

        assertThat(response.body().isEmpty()).isTrue();
    }

    @Test
    void bound_parameter_is_a_literal_not_sql_so_injection_returns_nothing() {
        // If the value were substituted into the SQL text, this would return every row.
        AdapterResponse response = adapter(USER + ":" + PASSWORD).execute(request("db.invalid", "PR-1' OR '1'='1"));

        assertThat(response.body().isEmpty()).isTrue();
    }

    @Test
    void non_select_template_is_refused_before_any_connection() {
        JdbcAdapter adapter = adapter(USER + ":" + PASSWORD);
        AdapterRequest write = new AdapterRequest(
                SOURCE, "JDBC", "db.invalid", "/pollution", "DELETE FROM pcb_clearance", Map.of(), "secret:none");

        assertThatThrownBy(() -> adapter.execute(write)).isInstanceOf(IllegalConnectorConfigurationException.class);
    }

    @Test
    void wrong_password_is_refused_without_echoing_it() {
        JdbcAdapter adapter = adapter(USER + ":not-the-password");

        assertThatThrownBy(() -> adapter.execute(request("db.invalid", "PR-1")))
                .isInstanceOf(JdbcTransportException.class)
                .hasMessageContaining(SOURCE)
                .hasMessageNotContaining("not-the-password");
    }

    @Test
    void missing_credential_fails_naming_the_secret_key_only() {
        JdbcAdapter adapter = adapter(null);

        assertThatThrownBy(() -> adapter.execute(request("db.invalid", "PR-1")))
                .isInstanceOf(JdbcTransportException.class)
                .hasMessageContaining(SourceCredentials.secretKey(SOURCE));
    }

    @Test
    void unconfigured_source_is_refused_not_guessed() {
        var client = new JdbcQueryClient(
                new JdbcSourceProperties(Map.of()), new SourceCredentials(new FixedSecrets(Map.of())));

        assertThatThrownBy(() -> client.queryOne("nope", "secret:none", SQL, Map.of("premiseId", "PR-1")))
                .isInstanceOf(JdbcTransportException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void simulator_host_still_reads_the_in_memory_backend_and_never_touches_jdbc() {
        // Client with no configured sources: any real call would throw.
        var client = new JdbcQueryClient(new JdbcSourceProperties(Map.of()), new SourceCredentials(new FixedSecrets(Map.of())));
        var adapter = new JdbcAdapter(new MockDepartmentBackend(), client);

        AdapterResponse response = adapter.execute(new AdapterRequest(
                SOURCE, "JDBC", MockDepartmentBackend.HOST, "/pollution", SQL, Map.of("premiseId", "PR-1"), "secret:none"));

        assertThat(response.body().get("clearanceStatus").asString()).isEqualTo("clear");
    }
}
