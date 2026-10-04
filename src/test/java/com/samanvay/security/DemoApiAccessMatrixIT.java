package com.samanvay.security;

import com.samanvay.SamanvayApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** Same matrix with the demo profile on, so the demo-only routes are covered too. */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@TestPropertySource(properties = {"samanvay.demo.tamper-endpoints=true", "samanvay.demo.chaos-endpoints=true"})
class DemoApiAccessMatrixIT extends AbstractApiAccessMatrixIT {

    @Override
    boolean demoProfile() {
        return true;
    }
}
