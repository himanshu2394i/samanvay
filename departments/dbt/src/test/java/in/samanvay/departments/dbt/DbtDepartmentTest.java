package in.samanvay.departments.dbt;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** DBT over real HTTP: OAuth2 client-credentials token, bearer-protected bank record, published manifest. */
class DbtDepartmentTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String CLIENT_ID = "samanvay-dev";
    static final String CLIENT_SECRET = "dbt-dev-secret-change-me";

    static HttpResponse<String> token(int port, String id, String secret, String grant) throws Exception {
        String form = "grant_type=" + grant + "&client_id=" + id + "&client_secret=" + secret + "&scope=bank.read";
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The bank record is asked for with a POST and a JSON body: a person ID does not belong in a URL. */
    static HttpResponse<String> bank(int port, String dbtId, String bearer) throws Exception {
        String body = dbtId == null ? "{}" : "{\"dbtId\":\"" + dbtId + "\"}";
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/bank"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> get(int port, String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class Normal {
        @LocalServerPort
        int port;

        @Test
        void manifest_is_public_and_declares_oauth2_and_no_resolve() throws Exception {
            HttpResponse<String> r = get(port, "/.well-known/samanvay/manifest", null);
            assertThat(r.statusCode()).isEqualTo(200);
            JsonNode m = JSON.readTree(r.body());
            assertThat(m.get("department").get("code").asString()).isEqualTo("DBT");
            assertThat(m.get("identity").get("personIdType").asString()).isEqualTo("DBT_ID");
            JsonNode bank = m.get("documents").get(0);
            assertThat(bank.get("category").asString()).isEqualTo("BANK_ACCOUNT");
            assertThat(bank.get("protocol").asString()).isEqualTo("REST");
            assertThat(bank.get("method").asString()).isEqualTo("POST");
            assertThat(bank.get("path").asString()).isEqualTo("/v1/bank");
            assertThat(bank.get("inputs").get(0).get("name").asString()).isEqualTo("dbtId");
            assertThat(bank.get("inputs").get(0).get("in").asString()).isEqualTo("body");
            assertThat(m.get("sample").get("personId").asString()).isEqualTo("DBT-1001");
            // one person ID unlocks the record: no resolve step
            assertThat(bank.has("lookup")).isFalse();
            JsonNode auth = bank.get("auth");
            assertThat(auth.get("scheme").asString()).isEqualTo("OAUTH2_CLIENT");
            assertThat(auth.get("tokenUrl").asString()).isEqualTo("/oauth/token");
            assertThat(auth.get("scopes").get(0).asString()).isEqualTo("bank.read");
            assertThat(auth.toString()).doesNotContain(CLIENT_SECRET);
        }

        @Test
        void manifest_publishes_its_journey_with_the_documents_it_needs() throws Exception {
            JsonNode j = JSON.readTree(get(port, "/.well-known/samanvay/manifest", null).body()).get("journeys");
            assertThat(j).hasSize(1);
            assertThat(j.get(0).get("code").asString()).isEqualTo("DBT_ACCOUNT_SEEDING");
            assertThat(j.get(0).get("referencePrefix").asString()).isEqualTo("DAS");
            assertThat(j.get(0).get("portalUrl").asString()).startsWith("http://localhost:8092/portal/");
            assertThat(j.get(0).get("requester").asString()).isEqualTo("DBT");
            assertThat(j.get(0).get("requiredCategories").get(0).get("category").asString()).isEqualTo("BANK_ACCOUNT");
            assertThat(j.get(0).get("requiredCategories").get(0).get("department").asString()).isEqualTo("DBT");
        }

        @Test
        void token_endpoint_issues_a_bearer_token_for_valid_client_credentials() throws Exception {
            HttpResponse<String> r = token(port, CLIENT_ID, CLIENT_SECRET, "client_credentials");
            assertThat(r.statusCode()).isEqualTo(200);
            JsonNode t = JSON.readTree(r.body());
            assertThat(t.get("token_type").asString()).isEqualTo("Bearer");
            assertThat(t.get("access_token").asString()).isNotBlank();
            assertThat(t.get("expires_in").asInt()).isPositive();
        }

        @Test
        void token_endpoint_refuses_a_wrong_secret_or_grant_type() throws Exception {
            assertThat(token(port, CLIENT_ID, "wrong", "client_credentials").statusCode()).isEqualTo(401);
            assertThat(token(port, "other", CLIENT_SECRET, "client_credentials").statusCode()).isEqualTo(401);
            assertThat(token(port, CLIENT_ID, CLIENT_SECRET, "password").statusCode()).isEqualTo(400);
        }

        @Test
        void bank_record_needs_a_valid_bearer_token() throws Exception {
            assertThat(bank(port, "DBT-1001", null).statusCode()).isEqualTo(401);
            assertThat(get(port, "/v1/bank?dbtId=DBT-1001", "not-a-token").statusCode()).isEqualTo(401);
        }

        @Test
        void the_old_get_form_with_the_id_in_the_url_is_no_longer_served() throws Exception {
            String bearer = JSON.readTree(token(port, CLIENT_ID, CLIENT_SECRET, "client_credentials").body()).get("access_token").asString();
            assertThat(get(port, "/v1/bank?dbtId=DBT-1001", bearer).statusCode()).isIn(404, 405);
        }

        @Test
        void a_non_json_or_wrong_typed_body_is_a_client_error_not_a_crash() throws Exception {
            String bearer = JSON.readTree(token(port, CLIENT_ID, CLIENT_SECRET, "client_credentials").body()).get("access_token").asString();
            for (String bad : new String[] {"not json", "[]", "{\"dbtId\":{\"x\":1}}", "{\"dbtId\":123}"}) {
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/bank"))
                        .header("Content-Type", "application/json").header("Authorization", "Bearer " + bearer)
                        .POST(HttpRequest.BodyPublishers.ofString(bad)).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(r.statusCode()).as(bad).isBetween(400, 422);
            }
        }

        @Test
        void bank_record_is_returned_for_a_known_person_with_a_token() throws Exception {
            String bearer = JSON.readTree(token(port, CLIENT_ID, CLIENT_SECRET, "client_credentials").body())
                    .get("access_token").asString();
            HttpResponse<String> r = bank(port, "DBT-1001", bearer);
            assertThat(r.statusCode()).isEqualTo(200);
            JsonNode b = JSON.readTree(r.body());
            assertThat(b.get("holderName").asString()).isEqualTo("Asha Patil");
            assertThat(b.get("accountRef").asString()).isEqualTo("XXXXXX1234");
            assertThat(b.get("ifscMasked").asString()).isEqualTo("SBIN0XXX300");
            assertThat(bank(port, "DBT-9999", bearer).statusCode()).isEqualTo(404);
            assertThat(bank(port, null, bearer).statusCode()).isEqualTo(400);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "dbt.oauth.token-ttl=PT0S")
    class ExpiredTokens {
        @LocalServerPort
        int port;

        @Test
        void an_expired_token_is_refused() throws Exception {
            String bearer = JSON.readTree(token(port, CLIENT_ID, CLIENT_SECRET, "client_credentials").body())
                    .get("access_token").asString();
            assertThat(bank(port, "DBT-1001", bearer).statusCode()).isEqualTo(401);
        }
    }
}
