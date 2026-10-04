package in.samanvay.departments.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** A department must not start with a published dev default, unless it says out loud that it is a demo. */
class StartupChecksTest {

    static final String STRONG = "a-very-long-random-session-secret-0123456789";

    static PortalProperties props(String baseUrl, String otp, String sessionSecret, String clientSecret) {
        return new PortalProperties("EDUCATION", "Education", "E", "#111", "#222", "#333", baseUrl, otp, sessionSecret, "key.jwk",
                new PortalProperties.Samanvay("https://samanvay.example", "https://auth.example/token", "dept-education", clientSecret));
    }

    static PortalProperties good() {
        return props("https://education.example", "834912", STRONG, "real-client-secret");
    }

    static MockEnvironment env(boolean demo) {
        return new MockEnvironment().withProperty("department.demo-mode", String.valueOf(demo));
    }

    @Test
    void a_properly_configured_department_starts() {
        assertThatCode(() -> new StartupChecks(good(), env(false))).doesNotThrowAnyException();
    }

    @Test
    void demo_mode_is_off_unless_asked_for() {
        assertThatThrownBy(() -> new StartupChecks(props("http://x", "123456", "", ""), new MockEnvironment())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void the_demo_flag_lets_every_weak_default_through() {
        assertThatCode(() -> new StartupChecks(props("http://localhost:8093", "123456", "short", "x-change-me"), env(true).withProperty("revenue.api-key", "a-change-me")))
                .doesNotThrowAnyException();
    }

    @Test
    void the_default_one_time_code_is_refused() {
        assertThatThrownBy(() -> new StartupChecks(props("https://e.example", "123456", STRONG, "s"), env(false))).hasMessageContaining("portal.otp-code");
    }

    @Test
    void a_session_secret_shorter_than_32_bytes_is_refused_but_blank_means_a_generated_one() {
        assertThatThrownBy(() -> new StartupChecks(props("https://e.example", "834912", "too-short", "s"), env(false)))
                .hasMessageContaining("portal.session-secret").hasMessageContaining("32");
        assertThatCode(() -> new StartupChecks(props("https://e.example", "834912", "", "s"), env(false))).doesNotThrowAnyException();
    }

    @Test
    void a_session_secret_that_is_a_change_me_default_is_refused() {
        assertThatThrownBy(() -> new StartupChecks(props("https://e.example", "834912", "dev-session-secret-for-local-runs-change-me", "s"), env(false)))
                .hasMessageContaining("portal.session-secret");
    }

    @Test
    void the_samanvay_client_secret_must_be_set_and_not_a_default() {
        assertThatThrownBy(() -> new StartupChecks(props("https://e.example", "834912", STRONG, ""), env(false))).hasMessageContaining("client-secret");
        assertThatThrownBy(() -> new StartupChecks(props("https://e.example", "834912", STRONG, "dev-change-me"), env(false))).hasMessageContaining("client-secret");
    }

    @Test
    void any_secret_like_property_still_holding_a_change_me_default_is_refused_without_printing_its_value() {
        for (String key : new String[] {"revenue.api-key", "dbt.oauth.client-secret", "education.wss.password", "revenue.manifest.discovery-key"}) {
            assertThatThrownBy(() -> new StartupChecks(good(), env(false).withProperty(key, "my-weird-value-change-me")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(key).hasMessageNotContaining("my-weird-value");
        }
        assertThatCode(() -> new StartupChecks(good(), env(false).withProperty("revenue.public-base-url", "x-change-me"))).as("not secret-like")
                .doesNotThrowAnyException();
    }

    @Test
    void outside_demo_mode_the_public_address_must_be_https() {
        assertThatThrownBy(() -> new StartupChecks(props("http://education.example", "834912", STRONG, "s"), env(false))).hasMessageContaining("https");
        assertThatThrownBy(() -> new StartupChecks(props(null, "834912", STRONG, "s"), env(false))).hasMessageContaining("https");
    }

    @Test
    void to_string_never_prints_a_secret_or_the_code() {
        String shown = props("https://e.example", "834912", STRONG, "real-client-secret").toString();
        assertThat(shown).doesNotContain("834912").doesNotContain(STRONG).doesNotContain("real-client-secret").contains("EDUCATION");
        assertThat(new PortalProperties.Samanvay("a", "b", "c", "real-client-secret").toString()).doesNotContain("real-client-secret").contains("c");
    }
}
