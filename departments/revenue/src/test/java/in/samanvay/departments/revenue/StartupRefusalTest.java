package in.samanvay.departments.revenue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The department refuses to start on published dev defaults or weak settings unless demo mode is on (tests run in the dev profile,
 * which turns it on, so each case here turns it off again on the command line).
 */
class StartupRefusalTest {

    /** A complete, strong configuration (a database address that is never connected to at start). */
    static List<String> strong() {
        List<String> a = new ArrayList<>(List.of("--department.demo-mode=false", "--server.port=0", "--spring.main.banner-mode=off",
                "--revenue.manifest.key-file=target/startup-revenue/key.jwk",
                "--portal.public-base-url=https://revenue.example.com", "--revenue.public-base-url=https://revenue.example.com",
                "--portal.otp-code=834912", "--portal.session-secret=a-session-secret-that-is-well-over-32-bytes-long",
                "--portal.samanvay.client-secret=a-real-client-secret-0001", "--revenue.db.url=jdbc:postgresql://127.0.0.1:1/none", "--revenue.db.password=pw"));
        a.add("--revenue.api-key=a-strong-api-key-0001");
        return a;
    }

    /** The strong configuration with one setting replaced. */
    static List<String> with(String name, String value) {
        List<String> a = strong();
        a.removeIf(x -> x.startsWith("--" + name + "="));
        a.add("--" + name + "=" + value);
        return a;
    }

    static ConfigurableApplicationContext run(List<String> args) {
        return new SpringApplicationBuilder(RevenueApplication.class).run(args.toArray(String[]::new));
    }

    @Test
    void a_properly_configured_department_starts_without_demo_mode() {
        try (ConfigurableApplicationContext ctx = run(strong())) {
            assertThat(ctx.isRunning()).isTrue();
        }
    }

    @Test
    void the_shipped_dev_defaults_are_refused_outside_demo_mode() {
        assertThatThrownBy(() -> run(List.of("--department.demo-mode=false", "--server.port=0", "--spring.main.banner-mode=off",
                "--revenue.manifest.key-file=target/startup-revenue/defaults.jwk", "--revenue.db.url=jdbc:postgresql://127.0.0.1:1/none")))
                .rootCause().isInstanceOf(IllegalStateException.class).hasMessageContaining("Refusing to start")
                .hasMessageContaining("portal.otp-code").hasMessageContaining("https").hasMessageContaining("portal.samanvay.client-secret")
                .hasMessageContaining("revenue.api-key");
    }

    @Test
    void a_session_secret_under_32_bytes_is_refused() {
        List<String> args = with("portal.session-secret", "too-short");
        assertThatThrownBy(() -> run(args)).rootCause().hasMessageContaining("portal.session-secret").hasMessageContaining("32");
    }

    @Test
    void the_default_one_time_code_is_refused() {
        List<String> args = with("portal.otp-code", "123456");
        assertThatThrownBy(() -> run(args)).rootCause().hasMessageContaining("portal.otp-code");
    }

    @Test
    void a_plain_http_public_address_is_refused() {
        List<String> args = with("portal.public-base-url", "http://revenue.example.com");
        assertThatThrownBy(() -> run(args)).rootCause().hasMessageContaining("https");
    }

    @Test
    void the_built_in_demo_accounts_are_not_available_outside_demo_mode() {
        List<String> args = strong();
        args.removeIf(a -> a.startsWith("--revenue.db."));
        assertThatThrownBy(() -> run(args)).rootCause().isInstanceOf(IllegalStateException.class).hasMessageContaining("demo accounts");
    }

    @Test
    void a_change_me_secret_is_refused_without_printing_its_value() {
        List<String> args = strong();
        args.removeIf(a -> a.startsWith("--revenue.api-key="));
        args.add("--revenue.api-key=still-the-dev-default-change-me");
        assertThatThrownBy(() -> run(args)).rootCause().hasMessageContaining("revenue.api-key").hasMessageNotContaining("still-the-dev-default");
    }
}
