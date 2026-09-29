package com.samanvay.connector.internal.protocol;

import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.AdapterResponse;
import com.samanvay.connector.api.ProtocolAdapter;
import com.samanvay.connector.internal.source.sftp.SftpCsvClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Simulator sources (the mock SFTP host, like the other adapters' mock host) read the in-memory
 * {@link MockSftpStore}. Every other source is real: the CSV is downloaded over SFTP by
 * {@link SftpCsvClient} and parsed the same way, so both paths return the same JSON shape.
 */
@Component
class SftpCsvAdapter implements ProtocolAdapter {

    private static final String ID_COLUMN = "propertyId";

    private final MockSftpStore store;
    private final SftpCsvClient sftp;
    private final JsonMapper json = JsonMapper.builder().build();

    SftpCsvAdapter(MockSftpStore store, SftpCsvClient sftp) {
        this.store = store;
        this.sftp = sftp;
    }

    @Override
    public String protocol() {
        return "SFTP_CSV";
    }

    @Override
    public AdapterResponse execute(AdapterRequest request) {
        String id = request.boundInputs() == null
                ? ""
                : request.boundInputs().getOrDefault(ID_COLUMN, request.boundInputs().values().stream().findFirst().orElse(""));
        MockSftpStore.JsonRow row;
        if (MockDepartmentBackend.HOST.equals(request.host())) {
            row = store.lookup(request.dataSourceCode(), ID_COLUMN, id);
        } else {
            String csv = sftp.download(request.dataSourceCode(), request.authConfigRef(), request.host(), request.endpoint());
            row = MockSftpStore.findRow(csv, ID_COLUMN, id);
        }
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
