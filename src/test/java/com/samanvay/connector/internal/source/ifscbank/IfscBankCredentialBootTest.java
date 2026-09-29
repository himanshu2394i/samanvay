package com.samanvay.connector.internal.source.ifscbank;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.connector.api.BankCheckAdapter.AccountStatus;
import com.samanvay.connector.api.BankCheckAdapter.BankCheckAnswer;
import com.samanvay.connector.api.BankCheckAdapter.BankCheckRequest;
import com.samanvay.connector.api.BankCheckAdapter.NameMatch;
import com.samanvay.connector.api.SourceOutcome;
import com.samanvay.connector.internal.source.SourceCredentials;
import com.samanvay.shared.SecretStore;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Credentials come from SecretStore in every mode (key source-ifsc-bank-credential).
 * A LIVE source with no usable credential stops the boot, and the message names
 * the source but never a secret value.
 */
class IfscBankCredentialBootTest {

    static final String KEY = "source-ifsc-bank-credential";

    /** Records every find() key; holds at most one value, for KEY. */
    static final class RecordingSecretStore implements SecretStore {
        final List<String> finds = Collections.synchronizedList(new ArrayList<>());
        final String value;

        RecordingSecretStore(String value) {
            this.value = value;
        }

        @Override
        public Secret resolve(String key) {
            throw new AssertionError("source credentials must use find(), never resolve() (which may generate)");
        }

        @Override
        public Optional<Secret> find(String key) {
            finds.add(key);
            return KEY.equals(key) && value != null
                    ? Optional.of(new Secret(value.getBytes(StandardCharsets.UTF_8)))
                    : Optional.empty();
        }
    }

    ApplicationContextRunner runner(RecordingSecretStore store, String mode, String baseUrl) {
        return new ApplicationContextRunner()
                .withUserConfiguration(IfscBankSourceConfig.class)
                .withBean(SourceCredentials.class, () -> new SourceCredentials(store))
                .withPropertyValues("samanvay.sources.ifsc-bank.base-url=" + baseUrl, "samanvay.sources.ifsc-bank.mode=" + mode);
    }

    @Test
    void live_without_credential_fails_the_boot_naming_the_source() {
        RecordingSecretStore store = new RecordingSecretStore(null);
        runner(store, "live", "https://bank-check.invalid").run(ctx -> {
            assertThat(ctx).hasFailed();
            String chain = chain(ctx.getStartupFailure());
            assertThat(chain).contains("source 'ifsc-bank' is configured LIVE").contains("missing").contains(KEY);
            assertThat(store.finds).contains(KEY);
        });
    }

    @Test
    void live_with_malformed_credential_fails_without_echoing_the_value() {
        String value = "garbage-secret-value-9f3Kq";
        runner(new RecordingSecretStore(value), "live", "https://bank-check.invalid").run(ctx -> {
            assertThat(ctx).hasFailed();
            String chain = chain(ctx.getStartupFailure());
            assertThat(chain).contains("source 'ifsc-bank'").contains("malformed").doesNotContain(value).doesNotContain("9f3Kq");
        });
    }

    @Test
    void live_with_credential_boots_and_never_prints_it() {
        runner(new RecordingSecretStore("live-key:live-secret-7Hq"), "live", "https://bank-check.invalid").run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(IfscBankClient.class);
            assertThat(new SourceCredentials(new RecordingSecretStore("live-key:live-secret-7Hq"))
                            .find("ifsc-bank").orElseThrow().toString())
                    .doesNotContain("live-secret-7Hq").doesNotContain("live-key");
        });
    }

    @Test
    void simulator_mode_boots_without_credential_and_calls_do_not_leave() {
        RecordingSecretStore store = new RecordingSecretStore(null);
        runner(store, "simulator", "http://127.0.0.1:9").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            SourceOutcome<BankCheckAnswer> outcome =
                    ctx.getBean(IfscBankClient.class).check(new BankCheckRequest("SBIN0000300", "00001000000001", "A"));
            assertThat(outcome).isEqualTo(new SourceOutcome.SourceFault<>(SourceOutcome.ReasonCode.CREDENTIAL_MISSING, false));
        });
    }

    @Test
    void simulator_mode_reads_the_credential_through_the_same_secret_store_call() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/bank-checks", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"accountStatus\":\"VALID\",\"nameMatch\":\"MATCH\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            RecordingSecretStore store = new RecordingSecretStore("sim-key:sim-secret");
            runner(store, "simulator", "http://127.0.0.1:" + server.getAddress().getPort()).run(ctx -> {
                assertThat(store.finds).as("simulator mode does no boot check").isEmpty();
                SourceOutcome<BankCheckAnswer> outcome =
                        ctx.getBean(IfscBankClient.class).check(new BankCheckRequest("SBIN0000300", "00001000000001", "A"));
                assertThat(outcome).isEqualTo(new SourceOutcome.Answered<>(new BankCheckAnswer(AccountStatus.VALID, NameMatch.MATCH), false));
                assertThat(store.finds).containsExactly(KEY);
                assertThat(authorization.get()).isEqualTo("Basic " + Base64.getEncoder()
                        .encodeToString("sim-key:sim-secret".getBytes(StandardCharsets.UTF_8)));
            });
        } finally {
            server.stop(0);
        }
    }

    private static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c).append('\n');
        }
        return sb.toString();
    }
}
