package in.samanvay.simulators;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Department simulators: spec-shaped stand-ins for external department APIs,
 * for local development and CI only. Never a live source.
 *
 * <p>Lives outside {@code com.samanvay} on purpose: the main application's
 * component scan and Spring Modulith's module detection are both rooted at
 * {@code com.samanvay}, so nothing here can be picked up by the monolith even
 * by accident. The main app talks to this process only over HTTP.
 */
@SpringBootApplication
public class SimulatorsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulatorsApplication.class, args);
    }
}
