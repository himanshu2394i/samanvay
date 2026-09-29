package com.samanvay.connector.internal.source.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.connector.internal.source.SourceCredentials;
import com.samanvay.shared.SecretStore;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** A LIVE JDBC source without a usable SecretStore credential stops the boot, naming the source but never a value. */
class JdbcSourceBootTest {

    static final String KEY = "source-pollution-jdbc-credential";
    static final String PREFIX = "samanvay.sources.jdbc.sources.pollution-jdbc.";

    record Store(String value) implements SecretStore {
        @Override
        public Secret resolve(String key) {
            throw new AssertionError("source credentials must use find()");
        }

        @Override
        public Optional<Secret> find(String key) {
            return KEY.equals(key) && value != null
                    ? Optional.of(new Secret(value.getBytes(StandardCharsets.UTF_8)))
                    : Optional.empty();
        }
    }

    ApplicationContextRunner runner(String value, String mode) {
        return new ApplicationContextRunner()
                .withUserConfiguration(JdbcSourceConfig.class)
                .withBean(SourceCredentials.class, () -> new SourceCredentials(new Store(value)))
                .withPropertyValues(
                        PREFIX + "mode=" + mode,
                        PREFIX + "jdbc-url=jdbc:postgresql://db.invalid:5432/pcb");
    }

    @Test
    void live_without_credential_fails_the_boot() {
        runner(null, "live").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(chain(ctx.getStartupFailure()))
                    .contains("source 'pollution-jdbc' is configured LIVE")
                    .contains("missing")
                    .contains(KEY);
        });
    }

    @Test
    void live_with_malformed_credential_fails_without_echoing_it() {
        runner("garbage-value-4Tq", "live").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(chain(ctx.getStartupFailure())).contains("malformed").doesNotContain("garbage-value-4Tq");
        });
    }

    @Test
    void live_with_credential_boots() {
        runner("user:pw-9Kd", "live").run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(JdbcQueryClient.class));
    }

    @Test
    void simulator_mode_boots_without_credential() {
        runner(null, "simulator").run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(JdbcQueryClient.class));
    }

    @Test
    void no_configured_sources_boots_and_configures_nothing() {
        new ApplicationContextRunner()
                .withUserConfiguration(JdbcSourceConfig.class)
                .withBean(SourceCredentials.class, () -> new SourceCredentials(new Store(null)))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(JdbcQueryClient.class).isConfigured("pollution-jdbc")).isFalse();
                });
    }

    private static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c).append('\n');
        }
        return sb.toString();
    }
}
