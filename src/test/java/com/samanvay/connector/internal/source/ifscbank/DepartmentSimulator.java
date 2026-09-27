package com.samanvay.connector.internal.source.ifscbank;

import java.io.IOException;
import java.io.UncheckedIOException;
import com.samanvay.connector.internal.source.SourceCredentials;
import com.samanvay.connector.internal.source.SourceMode;
import com.samanvay.shared.SecretStore;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import tools.jackson.databind.json.JsonMapper;

/**
 * The department simulator as a black box on an HTTP port, built from
 * {@code simulators/Dockerfile} (whose build stage also runs the simulator's own
 * tests). Started once per JVM and shared by the ITs in this package. The main
 * build has no Maven dependency on the simulator.
 */
final class DepartmentSimulator {

    static final String KEY_ID = "contract-key";
    static final String KEY_SECRET = "contract-secret";
    static final Path FIXTURES = Path.of("simulators/src/main/resources/fixtures/ifsc-bank.json");

    @SuppressWarnings("resource")
    static final GenericContainer<?> CONTAINER = new GenericContainer<>(
                    new ImageFromDockerfile("samanvay-simulators-contract", false)
                            .withFileFromPath(".", Path.of("simulators")))
            .withExposedPorts(8090)
            .withEnv("SIMULATOR_IFSC_BANK_KEY_ID", KEY_ID)
            .withEnv("SIMULATOR_IFSC_BANK_KEY_SECRET", KEY_SECRET)
            // Held longer than the clients' 1s read timeout, short enough not to pin threads.
            .withEnv("SIMULATOR_FAULTS_TIMEOUT_DELAY", "4s")
            .waitingFor(Wait.forHttp("/SBIN0000300").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

    static {
        CONTAINER.start();
    }

    private DepartmentSimulator() {}

    static URI baseUrl() {
        return URI.create("http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(8090));
    }

    /** A client whose credential comes through SecretStore, as in every mode. */
    static IfscBankClient client(String secret) {
        return new IfscBankClient(
                new IfscBankSourceProperties(baseUrl(), null, SourceMode.SIMULATOR, Duration.ofSeconds(2), Duration.ofSeconds(1)),
                new SourceCredentials(secretStore(KEY_ID + ":" + secret)));
    }

    /** A SecretStore holding only the ifsc-bank credential. */
    static SecretStore secretStore(String credential) {
        return key -> SourceCredentials.secretKey(IfscBankClient.SOURCE_CODE).equals(key)
                ? new SecretStore.Secret(credential.getBytes(StandardCharsets.UTF_8))
                : null;
    }

    /** The fixture account flagged canary_account_number: its full number must never surface. */
    static String canaryAccountNumber() {
        try {
            for (var a : JsonMapper.builder().build().readTree(Files.readString(FIXTURES)).get("accounts")) {
                if (a.path("canary_account_number").asBoolean(false)) {
                    return a.get("account_number").asString();
                }
            }
            throw new IllegalStateException("no canary account number in " + FIXTURES);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Canary holder names, read from the simulator's fixture FILE (not its classes: the boundary holds). */
    static List<String> canaryHolderNames() {
        try {
            List<String> names = new ArrayList<>();
            JsonMapper.builder().build().readTree(Files.readString(FIXTURES)).get("accounts")
                    .forEach(a -> names.add(a.get("holder_name").asString()));
            return names;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
