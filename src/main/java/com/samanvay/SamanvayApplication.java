package com.samanvay;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SamanvayApplication {

    static {
        // On India-locale machines the JVM's default zone resolves to the
        // legacy alias "Asia/Calcutta", which Postgres's tzdata does not
        // recognize as a valid TimeZone value, so the JDBC driver's
        // connection handshake fails before anything else runs.
        //
        // This MUST be a static initializer, not a statement inside
        // main(): @SpringBootTest boots this application via
        // SpringBootContextLoader, which never calls main() at all. A
        // static block runs the instant this class is loaded by ANY
        // caller - main() or Spring's test context - so it fires before
        // Flyway/DataSource beans are created either way. Putting it in
        // main() left every @SpringBootTest silently unfixed on this
        // exact class of machine, which is how DemoRehearsalIT failed
        // here while CI (UTC runners, bug can't manifest) stayed green.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(SamanvayApplication.class, args);
    }
}
