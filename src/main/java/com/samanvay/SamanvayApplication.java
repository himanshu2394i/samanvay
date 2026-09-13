package com.samanvay;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SamanvayApplication {

    public static void main(String[] args) {
        // On India-locale machines the JVM's default zone resolves to the
        // legacy alias "Asia/Calcutta", which Postgres's tzdata does not
        // recognize as a valid TimeZone value, so the JDBC driver's
        // connection handshake fails before anything else runs. Pinning
        // the JVM default here, before any connection is opened, fixes it
        // for every environment (dev machine, CI, container) uniformly.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(SamanvayApplication.class, args);
    }
}
