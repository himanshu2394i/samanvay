package com.samanvay.connector.internal.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.connector.api.AdapterRequest;
import com.samanvay.connector.api.IllegalConnectorConfigurationException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JdbcAdapterSelectOnlyTest {

    // These exercise the mock host and the SELECT-only guard, so the real JDBC client is never
    // reached; null makes that explicit. Real transport is covered by JdbcRealTransportTest.
    @Test
    void rejectsNonSelect() {
        JdbcAdapter adapter = new JdbcAdapter(new MockDepartmentBackend(), null);
        AdapterRequest req = new AdapterRequest(
                "pollution-jdbc-mock",
                "JDBC",
                "mock.samanvay.test",
                "/pollution",
                "DELETE FROM pcb_clearance",
                Map.of(),
                "secret:none");
        assertThatThrownBy(() -> adapter.execute(req)).isInstanceOf(IllegalConnectorConfigurationException.class);
    }

    @Test
    void acceptsParameterizedSelectOnMock() {
        JdbcAdapter adapter = new JdbcAdapter(new MockDepartmentBackend(), null);
        AdapterRequest req = new AdapterRequest(
                "pollution-jdbc-mock",
                "JDBC",
                "mock.samanvay.test",
                "/pollution",
                "SELECT clearance_status FROM pcb_clearance WHERE premise_id = :premiseId",
                Map.of("premiseId", "P-1"),
                "secret:none");
        assertThat(adapter.execute(req).body().get("clearanceStatus").asString()).isEqualTo("clear");
    }
}
