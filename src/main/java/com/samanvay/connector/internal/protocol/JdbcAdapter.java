package com.samanvay.connector.internal.protocol;

import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.AdapterResponse;
import com.samanvay.connector.api.IllegalConnectorConfigurationException;
import com.samanvay.connector.api.ProtocolAdapter;
import com.samanvay.connector.internal.source.jdbc.JdbcQueryClient;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Simulator sources (the mock host) read the in-memory {@link MockDepartmentBackend}. Every other
 * source is real: the parameterized SELECT is bound and run over JDBC by {@link JdbcQueryClient},
 * so both paths return the same JSON shape.
 */
@Component
class JdbcAdapter implements ProtocolAdapter {

    private final MockDepartmentBackend mocks;
    private final JdbcQueryClient jdbc;

    JdbcAdapter(MockDepartmentBackend mocks, JdbcQueryClient jdbc) {
        this.mocks = mocks;
        this.jdbc = jdbc;
    }

    @Override
    public String protocol() {
        return "JDBC";
    }

    /** A plain SQL identifier: what a department may name as its view or key column. Anything else is refused. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    @Override
    public AdapterResponse execute(AdapterRequest request) {
        String sql = request.template();
        Map<String, String> inputs = request.boundInputs();
        if (sql == null || sql.isBlank()) {
            // No SQL on the connector: build the one fixed SELECT from the department's published view + key column.
            String view = identifier(request.access().get("view"), "view");
            String key = identifier(request.access().get("key_column"), "key_column");
            sql = "SELECT * FROM " + view + " WHERE " + key + " = :" + key;
            inputs = Map.of(key, keyValue(inputs, key));
        }
        JdbcSqlGuard.assertSelectOnly(sql);
        JsonNode body;
        if (MockDepartmentBackend.HOST.equals(request.host())) {
            body = mocks.fetch(request.endpoint(), request.boundInputs());
        } else {
            body = jdbc.queryOne(request.dataSourceCode(), request.authConfigRef(), sql, inputs);
        }
        return new AdapterResponse(body, body.toString().length());
    }

    private static String identifier(String value, String what) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalConnectorConfigurationException(
                    "JDBC connector needs SQL or a valid " + what + " (a plain identifier); got an unusable value");
        }
        return value;
    }

    /** The key value: bound under the key column's own name, else the single bound input. */
    private static String keyValue(Map<String, String> inputs, String key) {
        if (inputs == null || inputs.isEmpty()) {
            return "";
        }
        String exact = inputs.get(key);
        return exact != null ? exact : inputs.values().iterator().next();
    }
}
