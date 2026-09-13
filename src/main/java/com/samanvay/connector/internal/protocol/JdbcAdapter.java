package com.samanvay.connector.internal.protocol;

import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.AdapterResponse;
import com.samanvay.connector.api.ProtocolAdapter;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
class JdbcAdapter implements ProtocolAdapter {

    private final MockDepartmentBackend mocks;

    JdbcAdapter(MockDepartmentBackend mocks) {
        this.mocks = mocks;
    }

    @Override
    public String protocol() {
        return "JDBC";
    }

    @Override
    public AdapterResponse execute(AdapterRequest request) {
        JdbcSqlGuard.assertSelectOnly(request.template());
        if (MockDepartmentBackend.HOST.equals(request.host())) {
            JsonNode body = mocks.fetch(request.endpoint(), request.boundInputs());
            return new AdapterResponse(body, body.toString().length());
        }
        throw new UnsupportedOperationException("live JDBC departments are not wired in the demo");
    }
}
