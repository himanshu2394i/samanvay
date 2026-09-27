package com.samanvay.security;

import com.samanvay.SamanvayApplication;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiAccessMatrixIT extends AbstractApiAccessMatrixIT {

    @Override
    boolean demoProfile() {
        return false;
    }
}
