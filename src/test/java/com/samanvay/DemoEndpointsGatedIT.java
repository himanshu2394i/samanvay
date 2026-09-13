package com.samanvay;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.samanvay.shared.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = SamanvayApplication.class)
@AutoConfigureMockMvc
class DemoEndpointsGatedIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void tamperAndChaosAreAbsentWithoutDemoProfile() throws Exception {
        mvc.perform(post("/api/audit/demo/tamper/1")).andExpect(status().isNotFound());
        mvc.perform(post("/api/connector/chaos/revenue-rest-mock/kill")).andExpect(status().isNotFound());
    }
}
