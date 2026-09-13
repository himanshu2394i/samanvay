package com.samanvay.connector.internal.protocol;

import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
class MockDepartmentBackend {

    static final String HOST = "mock.samanvay.test";
    static final String INCOME_TOKEN = "INCOME-AMT-998877";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    JsonNode fetch(String endpoint, Map<String, String> inputs) {
        ObjectNode n = JSON.createObjectNode();
        String path = endpoint == null ? "" : endpoint;
        if (path.contains("income")) {
            n.put("annualIncome", INCOME_TOKEN);
            n.put("holderName", "Ramesh");
            return n;
        }
        if (path.contains("caste")) {
            n.put("casteCategory", "obc");
            return n;
        }
        if (path.contains("marks") || path.contains("soap")) {
            n.put("percentage", "81");
            return n;
        }
        if (path.contains("bank")) {
            n.put("accountRef", "XXXX1234");
            return n;
        }
        n.put("ok", true);
        return n;
    }

    String soapMarks() {
        return "<Envelope><percentage>81</percentage></Envelope>";
    }
}
