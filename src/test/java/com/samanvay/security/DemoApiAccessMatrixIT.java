package com.samanvay.security;

import com.samanvay.SamanvayApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Same matrix with the demo profile on, so the demo-only routes are covered too. */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
class DemoApiAccessMatrixIT extends AbstractApiAccessMatrixIT {

    @Override
    boolean demoProfile() {
        return true;
    }
}
