package com.samanvay.connector.internal.protocol;

import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.AdapterResponse;
import com.samanvay.connector.api.ProtocolAdapter;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
class SftpCsvAdapter implements ProtocolAdapter {

    private final MockSftpStore store;
    private final JsonMapper json = JsonMapper.builder().build();

    SftpCsvAdapter(MockSftpStore store) {
        this.store = store;
    }

    @Override
    public String protocol() {
        return "SFTP_CSV";
    }

    @Override
    public AdapterResponse execute(AdapterRequest request) {
        String id = request.boundInputs() == null
                ? ""
                : request.boundInputs().getOrDefault("propertyId", request.boundInputs().values().stream().findFirst().orElse(""));
        var row = store.lookup(request.dataSourceCode(), "propertyId", id);
        ObjectNode body = json.createObjectNode();
        if (row == null) {
            body.put("propertyRef", "UNKNOWN");
            return new AdapterResponse(body, 0);
        }
        for (int i = 0; i < row.headers().length && i < row.cols().length; i++) {
            body.put(row.headers()[i], row.cols()[i]);
        }
        JsonNode node = body;
        return new AdapterResponse(node, request.toString().length());
    }
}
