package com.samanvay.connector.internal.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.shared.SecretStore;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** How a data source's non-secret auth spec is read, and how its secret values (named parameters) are looked up. */
class AuthSpecAndCredentialParamsTest {

    static SourceCredentials creds(Map<String, String> secrets) {
        SecretStore store = key -> secrets.containsKey(key) ? new SecretStore.Secret(secrets.get(key).getBytes(StandardCharsets.UTF_8)) : null;
        return new SourceCredentials(store);
    }

    // --- AuthSpec ---------------------------------------------------------------------------------------------

    @Test
    void a_full_manifest_style_spec_is_read() {
        AuthSpec s = AuthSpec.of("API_KEY", "{\"scheme\":\"API_KEY\",\"parameters\":[{\"name\":\"X-Api-Key\",\"in\":\"header\",\"secret\":true}]}");
        assertThat(s.scheme()).isEqualTo("API_KEY");
        assertThat(s.parameters()).hasSize(1);
        assertThat(s.parameters().get(0).name()).isEqualTo("X-Api-Key");
        assertThat(s.parameters().get(0).in()).isEqualTo("header");
        assertThat(s.parameters().get(0).secret()).isTrue();
    }

    @Test
    void oauth_details_and_password_type_are_read() {
        AuthSpec o = AuthSpec.of("OAUTH2_CLIENT", "{\"scheme\":\"OAUTH2_CLIENT\",\"tokenUrl\":\"/oauth/token\",\"scopes\":[\"bank.read\"]}");
        assertThat(o.tokenUrl()).isEqualTo("/oauth/token");
        assertThat(o.scopes()).containsExactly("bank.read");
        assertThat(AuthSpec.of("WS_SECURITY_USERNAME", "{\"scheme\":\"WS_SECURITY_USERNAME\",\"passwordType\":\"PasswordText\"}").passwordType())
                .isEqualTo("PasswordText");
    }

    @Test
    void with_no_spec_the_stored_auth_type_decides_and_none_means_no_auth() {
        assertThat(AuthSpec.of("NONE", null).scheme()).isEqualTo("NONE");
        assertThat(AuthSpec.of("NONE", "{}").scheme()).isEqualTo("NONE");
        assertThat(AuthSpec.of("PASSWORD", "{}").scheme()).isEqualTo("PASSWORD");
        assertThat(AuthSpec.of(null, null).scheme()).isEqualTo("NONE");
        assertThat(AuthSpec.of("", "").scheme()).isEqualTo("NONE");
    }

    @Test
    void the_spec_scheme_wins_over_the_stored_auth_type() {
        assertThat(AuthSpec.of("NONE", "{\"scheme\":\"API_KEY\"}").scheme()).isEqualTo("API_KEY");
    }

    @Test
    void a_malformed_spec_is_treated_as_the_stored_auth_type_not_a_crash() {
        AuthSpec s = AuthSpec.of("API_KEY", "{not json");
        assertThat(s.scheme()).isEqualTo("API_KEY");
        assertThat(s.parameters()).isEmpty();
    }

    // --- credential parameters ---------------------------------------------------------------------------------

    @Test
    void a_json_secret_gives_named_parameters() {
        SourceCredentials c = creds(Map.of("source-rev-credential", "{\"X-Api-Key\":\"k1\",\"X-Client\":\"samanvay\"}"));
        assertThat(c.params("rev")).containsEntry("X-Api-Key", "k1").containsEntry("X-Client", "samanvay");
    }

    @Test
    void a_legacy_key_id_and_secret_is_exposed_as_username_and_password_too() {
        SourceCredentials c = creds(Map.of("source-sftp-credential", "fixtureuser:fixturepass"));
        assertThat(c.params("sftp")).containsEntry("username", "fixtureuser").containsEntry("password", "fixturepass");
        // the old accessor is unchanged
        assertThat(c.find("sftp")).hasValueSatisfying(cr -> {
            assertThat(cr.keyId()).isEqualTo("fixtureuser");
            assertThat(cr.keySecret()).isEqualTo("fixturepass");
        });
    }

    @Test
    void a_json_secret_with_username_and_password_also_works_for_the_old_accessor() {
        SourceCredentials c = creds(Map.of("source-db-credential", "{\"username\":\"agri_ro\",\"password\":\"p:w\"}"));
        assertThat(c.find("db")).hasValueSatisfying(cr -> {
            assertThat(cr.keyId()).isEqualTo("agri_ro");
            assertThat(cr.keySecret()).isEqualTo("p:w");
        });
    }

    @Test
    void a_missing_or_malformed_secret_gives_no_parameters() {
        assertThat(creds(Map.of()).params("nope")).isEmpty();
        assertThat(creds(Map.of("source-x-credential", "{broken")).params("x")).isEmpty();
        assertThat(creds(Map.of("source-y-credential", "")).params("y")).isEmpty();
    }

    @Test
    void non_string_values_in_a_json_secret_are_ignored_not_trusted() {
        SourceCredentials c = creds(Map.of("source-z-credential", "{\"ok\":\"v\",\"bad\":{\"n\":1},\"num\":5}"));
        assertThat(c.params("z")).containsOnlyKeys("ok");
    }

    @Test
    void parameters_are_never_printed_by_toString() {
        assertThat(creds(Map.of()).toString()).doesNotContain("source-");
        assertThat(new AuthSpec.Param("p", "header", true).toString()).doesNotContain("secret-value");
    }
}
