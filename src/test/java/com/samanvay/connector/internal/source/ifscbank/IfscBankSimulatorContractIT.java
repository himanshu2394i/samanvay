package com.samanvay.connector.internal.source.ifscbank;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * Runs {@link IfscBankSourceContract} against the department simulator, built
 * from {@code simulators/Dockerfile} by Testcontainers. The main build has no
 * Maven dependency on the simulator: it's a black box on an HTTP port, exactly
 * as the live source will be.
 */
class IfscBankSimulatorContractIT extends IfscBankSourceContract {

    static final String KEY_ID = "contract-key";
    static final String KEY_SECRET = "contract-secret";

    @SuppressWarnings("resource")
    static final GenericContainer<?> SIMULATOR = new GenericContainer<>(
                    new ImageFromDockerfile("samanvay-simulators-contract", false)
                            .withFileFromPath(".", Path.of("simulators")))
            .withExposedPorts(8090)
            .withEnv("SIMULATOR_IFSC_BANK_KEY_ID", KEY_ID)
            .withEnv("SIMULATOR_IFSC_BANK_KEY_SECRET", KEY_SECRET)
            // Held longer than the client's read timeout below, short enough not to pin threads.
            .withEnv("SIMULATOR_FAULTS_TIMEOUT_DELAY", "4s")
            .waitingFor(Wait.forHttp("/SBIN0000300").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

    static IfscBankClient client;
    static IfscBankClient wrongCredentials;

    @BeforeAll
    static void start() {
        SIMULATOR.start();
        URI base = URI.create("http://" + SIMULATOR.getHost() + ":" + SIMULATOR.getMappedPort(8090));
        client = new IfscBankClient(
                new IfscBankSourceProperties(base, null, KEY_ID, KEY_SECRET, Duration.ofSeconds(2), Duration.ofSeconds(1)));
        wrongCredentials = new IfscBankClient(
                new IfscBankSourceProperties(base, null, KEY_ID, "not-the-secret", Duration.ofSeconds(2), Duration.ofSeconds(1)));
    }

    @AfterAll
    static void stop() {
        SIMULATOR.stop();
    }

    @Override
    protected IfscBankClient client() {
        return client;
    }

    @Override
    protected IfscBankClient clientWithWrongCredentials() {
        return wrongCredentials;
    }

    @Override
    protected boolean expectSimulatorMarker() {
        return true;
    }

    /** Mirrors simulators/src/main/resources/fixtures/ifsc-bank.json (real RBI branches, synthetic accounts). */
    @Override
    protected Fixtures fixtures() {
        return new Fixtures(
                "SBIN0000300",
                "State Bank of India",
                "ABCD0123456",
                "SBIN000030",
                new Account("SBIN0000300", "00001000000001", "ASHA SIMULATED PATIL"),
                new Account("BKID0000150", "00001000000004", "VIKAS SIMULATED JADHAV"),
                new Account("MAHB0000001", "00001999999999", "NOBODY SIMULATED"),
                Optional.of("SAMS0000408"),
                Optional.of("SAMS0000500"),
                Optional.of("SAMS0000422"),
                Optional.of(new Account("SBIN0000300", "00009000000408", "TIMEOUT SIMULATED")),
                Optional.of(new Account("SBIN0000300", "00009000000500", "FAULT SIMULATED")));
    }
}
