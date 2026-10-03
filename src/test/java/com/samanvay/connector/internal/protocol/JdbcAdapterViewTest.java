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
import com.samanvay.shared.SecretStore;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A department publishes a read-only VIEW and its key column (manifest access.jdbc), never SQL. Samanvay builds the one
 * fixed SELECT itself, from identifiers it has validated. Real JDBC against in-process H2, as JdbcRealTransportTest.
 */
class JdbcAdapterViewTest {

    static final String SOURCE = "agri-jdbc";
    static final String USER = "agri_ro";
    static final String PASSWORD = "agri-ro-Zx81";

    String url;
    Connection keepAlive;

    @BeforeEach
    void startDb() throws Exception {
        url = "jdbc:h2:mem:agri_" + UUID.randomUUID().toString().replace("-", "");
        keepAlive = DriverManager.getConnection(url + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement st = keepAlive.createStatement()) {
            st.execute("CREATE TABLE farmer (agri_person_id VARCHAR(32) PRIMARY KEY, farmer_name VARCHAR(80), village VARCHAR(80), internal_notes VARCHAR(200))");
            st.execute("INSERT INTO farmer VALUES ('AG-1001', 'Asha Patil', 'Ojhar', 'internal: audit pending')");
            st.execute("INSERT INTO farmer VALUES ('AG-1002', 'Ravi Deshmukh', 'Loni', 'internal: none')");
            st.execute("CREATE VIEW v_farmer_record AS SELECT agri_person_id, farmer_name, village FROM farmer");
            st.execute("CREATE USER " + USER + " PASSWORD '" + PASSWORD + "'");
            st.execute("GRANT SELECT ON v_farmer_record TO " + USER);
        }
    }

    @AfterEach
    void stopDb() throws Exception {
        keepAlive.close();
    }

    JdbcAdapter adapter() {
        SecretStore store = k -> k.equals(SourceCredentials.secretKey(SOURCE))
                ? new SecretStore.Secret((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8)) : null;
        var client = new JdbcQueryClient(
                new JdbcSourceProperties(Map.of(SOURCE, new JdbcSourceProperties.Source(SourceMode.LIVE, url, Duration.ofSeconds(5), 1000))),
                new SourceCredentials(store));
        return new JdbcAdapter(new MockDepartmentBackend(), client);
    }

    AdapterRequest view(String view, String keyColumn, String keyValue, String template) {
        return new AdapterRequest(SOURCE, "JDBC", "db.invalid", "/farmer", template, Map.of("agri_person_id", keyValue), "secret:none",
                null, null, Map.of("view", view, "key_column", keyColumn));
    }

    @Test
    void the_fixed_select_is_built_from_the_view_and_key_column_and_returns_only_the_views_columns() {
        AdapterResponse r = adapter().execute(view("v_farmer_record", "agri_person_id", "AG-1001", null));
        assertThat(r.body().get("farmer_name").asString()).isEqualTo("Asha Patil");
        assertThat(r.body().get("village").asString()).isEqualTo("Ojhar");
        assertThat(r.body().has("internal_notes")).isFalse();
    }

    @Test
    void an_unknown_key_returns_an_empty_body() {
        assertThat(adapter().execute(view("v_farmer_record", "agri_person_id", "AG-404", null)).body().isEmpty()).isTrue();
    }

    @Test
    void the_key_value_is_bound_never_substituted() {
        assertThat(adapter().execute(view("v_farmer_record", "agri_person_id", "AG-1001' OR '1'='1", null)).body().isEmpty()).isTrue();
    }

    @Test
    void the_account_cannot_read_the_base_table_even_if_a_manifest_asked_for_it() {
        assertThatThrownBy(() -> adapter().execute(view("farmer", "agri_person_id", "AG-1001", null))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void a_view_or_key_column_that_is_not_a_plain_identifier_is_refused_before_any_query() {
        for (String bad : new String[] {"v; DROP TABLE farmer", "v_farmer_record WHERE 1=1 --", "a b", "", "1abc", "v'x", "a.b.c"}) {
            assertThatThrownBy(() -> adapter().execute(view(bad, "agri_person_id", "AG-1001", null)))
                    .as("view=" + bad).isInstanceOf(IllegalConnectorConfigurationException.class);
            assertThatThrownBy(() -> adapter().execute(view("v_farmer_record", bad, "AG-1001", null)))
                    .as("key=" + bad).isInstanceOf(IllegalConnectorConfigurationException.class);
        }
    }

    @Test
    void an_explicit_sql_template_still_wins_so_existing_connectors_are_unchanged() {
        AdapterRequest r = new AdapterRequest(SOURCE, "JDBC", "db.invalid", "/farmer",
                "SELECT farmer_name FROM v_farmer_record WHERE agri_person_id = :agri_person_id", Map.of("agri_person_id", "AG-1002"),
                "secret:none", null, null, Map.of("view", "ignored", "key_column", "ignored"));
        assertThat(adapter().execute(r).body().get("farmer_name").asString()).isEqualTo("Ravi Deshmukh");
    }

    @Test
    void with_neither_a_template_nor_a_view_the_connector_is_misconfigured() {
        AdapterRequest r = new AdapterRequest(SOURCE, "JDBC", "db.invalid", "/farmer", null, Map.of("agri_person_id", "AG-1001"), "secret:none",
                null, null, Map.of());
        assertThatThrownBy(() -> adapter().execute(r)).isInstanceOf(IllegalConnectorConfigurationException.class);
    }

    @Test
    void the_key_is_taken_from_the_single_bound_input_when_it_is_named_differently() {
        AdapterRequest r = new AdapterRequest(SOURCE, "JDBC", "db.invalid", "/farmer", null, Map.of("farmerId", "AG-1002"), "secret:none",
                null, null, Map.of("view", "v_farmer_record", "key_column", "agri_person_id"));
        assertThat(adapter().execute(r).body().get("farmer_name").asString()).isEqualTo("Ravi Deshmukh");
    }
}
