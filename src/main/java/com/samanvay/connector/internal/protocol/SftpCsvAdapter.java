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

    /** Default key column for connectors that do not declare one (the original demo CSV). */
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
        String keyColumn = request.access().getOrDefault("key_column", ID_COLUMN);
        String id = request.boundInputs() == null
                ? ""
                : request.boundInputs().getOrDefault(keyColumn, request.boundInputs().values().stream().findFirst().orElse(""));
        MockSftpStore.JsonRow row;
        if (MockDepartmentBackend.HOST.equals(request.host())) {
            row = store.lookup(request.dataSourceCode(), keyColumn, id);
        } else {
            String csv = sftp.download(request.dataSourceCode(), request.authConfigRef(), request.host(), request.endpoint());
            row = MockSftpStore.findRow(csv, keyColumn, id);
        }
        ObjectNode body = json.createObjectNode();
        if (row == null) {
            return new AdapterResponse(body, 0); // an empty object: no such record (the runtime reads it as NotFound, never as a row)
        }
        for (int i = 0; i < row.headers().length && i < row.cols().length; i++) {
            body.put(row.headers()[i], row.cols()[i]);
        }
        JsonNode node = body;
        return new AdapterResponse(node, request.toString().length());
    }
}
