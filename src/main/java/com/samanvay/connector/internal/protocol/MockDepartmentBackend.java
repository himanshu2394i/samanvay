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
            n.put("annualIncomeDisplay", "₹ 1,85,000");
            n.put("holderName", "Ramesh");
            n.put("district", "Pune");
            n.put("issuerOffice", "Tahsildar, Haveli");
            return n;
        }
        if (path.contains("caste")) {
            n.put("casteCategory", "OBC");
            n.put("certificateNo", "CASTE-DEMO-441");
            return n;
        }
        if (path.contains("marks") || path.contains("soap")) {
            n.put("percentage", "81");
            n.put("exam", "HSC 2024");
            return n;
        }
        if (path.contains("bank")) {
            n.put("accountRef", "XXXX1234");
            n.put("ifscMasked", "XXXX0XX123");
            return n;
        }
        if (path.contains("fire")) {
            n.put("nocStatus", "valid");
            n.put("nocStatusDisplay", "Valid");
            n.put("nocNo", "FIRE-NOC-DEMO-19");
            return n;
        }
        if (path.contains("pollution")) {
            n.put("clearanceStatus", "clear");
            n.put("clearanceStatusDisplay", "Consent granted");
            n.put("pcbCategory", "Orange");
            return n;
        }
        if (path.contains("property")) {
            n.put("propertyId", "PMC-12-4401");
            n.put("ward", "Kothrud");
            return n;
        }
        if (path.contains("land")) {
            n.put("surveyNo", "12/4-A");
            n.put("village", "Wagholi");
            n.put("area", "1.20 ha");
            return n;
        }
        if (path.contains("crop")) {
            n.put("ok", true);
            n.put("season", "Kharif 2025");
            n.put("crop", "Soybean");
            return n;
        }
        n.put("ok", true);
        return n;
    }

    String soapMarks() {
        return "<Envelope><percentage>81</percentage></Envelope>";
    }
}
