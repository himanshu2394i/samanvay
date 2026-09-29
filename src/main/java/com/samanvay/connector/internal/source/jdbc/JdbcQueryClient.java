package com.samanvay.connector.internal.source.jdbc;

import com.samanvay.connector.internal.source.SourceCredentials;
import com.samanvay.connector.internal.source.SourceCredentials.Credential;
import java.sql.ResultSetMetaData;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs a single parameterized SELECT against a real department database over JDBC.
 *
 * <p>The username and password are the {@code keyId:keySecret} pair from SecretStore (see
 * {@link SourceCredentials}); they are read on every call, so credential rotation needs no restart.
 * The SQL comes from the connector catalog with Spring-style {@code :name} placeholders, and the
 * values are <em>bound</em> as parameters ({@link NamedParameterJdbcTemplate}) — never substituted
 * into the SQL text — so a value can never change the statement. Callers guard SELECT-only upstream
 * (JdbcSqlGuard). Column labels are lowercased so H2 (test) and PostgreSQL (prod) return the same
 * JSON keys.
 */
public class JdbcQueryClient {

    private final JdbcSourceProperties properties;
    private final SourceCredentials credentials;
    private final JsonMapper json = JsonMapper.builder().build();

    public JdbcQueryClient(JdbcSourceProperties properties, SourceCredentials credentials) {
        this.properties = properties;
        this.credentials = credentials;
    }

    /** True when a real-transport source is configured for this data source code. */
    public boolean isConfigured(String dataSourceCode) {
        return properties.sources().containsKey(dataSourceCode);
    }

    /**
     * @param dataSourceCode picks the configuration entry
     * @param authConfigRef {@code secret:<code>} names another source's SecretStore credential;
     *     anything else (e.g. {@code secret:none}) uses {@code dataSourceCode}'s own credential
     * @param sql the parameterized SELECT ({@code :name} placeholders)
     * @param boundInputs values bound to the placeholders, by name
     * @return the first row as a JSON object (lowercased column labels), or an empty object when
     *     the query matches no row
     */
    public JsonNode queryOne(String dataSourceCode, String authConfigRef, String sql, Map<String, String> boundInputs) {
        JdbcSourceProperties.Source source = properties.sources().get(dataSourceCode);
        if (source == null) {
            throw new JdbcTransportException("JDBC source '" + dataSourceCode + "' is not configured (samanvay.sources.jdbc.sources."
                    + dataSourceCode + ")");
        }
        String credentialCode = credentialCode(dataSourceCode, authConfigRef);
        Credential credential = credentials.find(credentialCode).orElseThrow(() -> new JdbcTransportException(
                "JDBC source '" + dataSourceCode + "' credential is missing or malformed in SecretStore (key "
                        + SourceCredentials.secretKey(credentialCode) + ", expected username:password)"));

        DriverManagerDataSource dataSource = new DriverManagerDataSource(source.jdbcUrl(), credential.keyId(), credential.keySecret());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.setMaxRows(source.maxRows());
        jdbc.setQueryTimeout((int) Math.max(1, source.queryTimeout().toSeconds()));
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (boundInputs != null) {
            boundInputs.forEach(params::addValue);
        }
        try {
            JsonNode row = new NamedParameterJdbcTemplate(jdbc).query(sql, params, rs -> {
                ObjectNode body = json.createObjectNode();
                if (!rs.next()) {
                    return body;
                }
                ResultSetMetaData meta = rs.getMetaData();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    body.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), rs.getString(i));
                }
                return (JsonNode) body;
            });
            return row == null ? json.createObjectNode() : row;
        } catch (RuntimeException e) {
            // Covers Spring's DataAccessException (connection/auth/query failures). The cause is kept
            // for diagnosis; only the exception type is put in the message, never the SQL, parameter
            // values or the credential.
            throw new JdbcTransportException("JDBC query failed for source '" + dataSourceCode + "': "
                    + e.getClass().getSimpleName(), e);
        }
    }

    static String credentialCode(String dataSourceCode, String authConfigRef) {
        if (authConfigRef != null && authConfigRef.startsWith("secret:")) {
            String named = authConfigRef.substring("secret:".length()).trim();
            if (!named.isEmpty() && !"none".equals(named)) {
                return named;
            }
        }
        return dataSourceCode;
    }
}
