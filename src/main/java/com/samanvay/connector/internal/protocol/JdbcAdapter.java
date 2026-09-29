package com.samanvay.connector.internal.protocol;

import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.AdapterResponse;
import com.samanvay.connector.api.ProtocolAdapter;
import com.samanvay.connector.internal.source.jdbc.JdbcQueryClient;
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

    @Override
    public AdapterResponse execute(AdapterRequest request) {
        JdbcSqlGuard.assertSelectOnly(request.template());
        JsonNode body;
        if (MockDepartmentBackend.HOST.equals(request.host())) {
            body = mocks.fetch(request.endpoint(), request.boundInputs());
        } else {
            body = jdbc.queryOne(request.dataSourceCode(), request.authConfigRef(), request.template(), request.boundInputs());
        }
        return new AdapterResponse(body, body.toString().length());
    }
}
