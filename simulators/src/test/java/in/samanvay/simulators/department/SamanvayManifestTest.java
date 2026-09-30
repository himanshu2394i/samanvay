package in.samanvay.simulators.department;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The department publishes a standardized Samanvay discovery manifest at a well-known path. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SamanvayManifestTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Test
    void publishes_the_documents_it_holds_and_journeys_it_offers() throws Exception {
        HttpResponse<String> r = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/.well-known/samanvay/manifest")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).startsWith("application/json"));

        JsonNode m = JSON.readTree(r.body());
        assertThat(m.get("manifestVersion").asInt()).isEqualTo(1);
        assertThat(m.get("department").get("code").asString()).isEqualTo("SANDBOX");

        JsonNode docs = m.get("documents");
        assertThat(docs).hasSize(3);

        // the BANK_ACCOUNT document declares how to fetch it and its field structure
        JsonNode bank = null;
        for (JsonNode d : docs) {
            if ("BANK_ACCOUNT".equals(d.get("category").asString())) {
                bank = d;
            }
        }
        assertThat(bank).isNotNull();
        assertThat(bank.get("protocol").asString()).isEqualTo("REST");
        assertThat(bank.get("path").asString()).isEqualTo("/bank");
        assertThat(bank.get("inputs").get(0).get("name").asString()).isEqualTo("dbtId");
        boolean hasAccountRef = false;
        for (JsonNode f : bank.get("fields")) {
            if ("accountRef".equals(f.get("name").asString())) {
                hasAccountRef = true;
            }
        }
        assertThat(hasAccountRef).isTrue();

        // a journey lists the document categories it requires
        assertThat(m.get("journeys").get(0).get("requiredCategories").toString()).contains("BANK_ACCOUNT");
    }
}
