package com.samanvay.connector.internal.source.sftp;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.connector.internal.source.SourceCredentials;
import com.samanvay.shared.SecretStore;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** A LIVE SFTP source without a usable SecretStore credential stops the boot, naming the source but never a value. */
class SftpSourceBootTest {

    static final String KEY = "source-municipal-sftp-credential";
    static final String PREFIX = "samanvay.sources.sftp.sources.municipal-sftp.";

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
                .withUserConfiguration(SftpSourceConfig.class)
                .withBean(SourceCredentials.class, () -> new SourceCredentials(new Store(value)))
                .withPropertyValues(
                        PREFIX + "mode=" + mode,
                        PREFIX + "host=sftp.invalid",
                        PREFIX + "remote-path=/outbound/property.csv",
                        PREFIX + "host-key-sha256=SHA256:fixture");
    }

    @Test
    void live_without_credential_fails_the_boot() {
        runner(null, "live").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(chain(ctx.getStartupFailure()))
                    .contains("source 'municipal-sftp' is configured LIVE")
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
        runner("user:pw-9Kd", "live").run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(SftpCsvClient.class));
    }

    @Test
    void simulator_mode_boots_without_credential() {
        runner(null, "simulator").run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(SftpCsvClient.class));
    }

    @Test
    void no_configured_sources_boots_and_configures_nothing() {
        new ApplicationContextRunner()
                .withUserConfiguration(SftpSourceConfig.class)
                .withBean(SourceCredentials.class, () -> new SourceCredentials(new Store(null)))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(SftpCsvClient.class).isConfigured("municipal-sftp")).isFalse();
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
