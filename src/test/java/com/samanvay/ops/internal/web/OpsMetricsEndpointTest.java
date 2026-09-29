package com.samanvay.ops.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.samanvay.ops.internal.service.OpsMetricsService;
import com.samanvay.orchestration.api.JourneyService;
import com.samanvay.shared.OpsMetrics;
import com.samanvay.shared.test.TestTokens;
import com.samanvay.tracking.api.SlaOverview;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * {@code GET /api/ops/metrics} through the real security chain ({@code SecurityConfig}: real JWT
 * validation against the per-JVM test realms), the real controller and the real service over a real
 * registry. Docker-free. The full route x role matrix is asserted again in {@code ApiAccessMatrixIT}.
 */
@SpringJUnitWebConfig(classes = OpsMetricsEndpointTest.Config.class)
class OpsMetricsEndpointTest {

    private static final String ROUTE = "/api/ops/metrics";

    /** SecurityConfig and its startup check are package-private in shared.security, so import them by name. */
    static class SecurityImports implements ImportSelector {
        @Override
        public String[] selectImports(AnnotationMetadata importingClassMetadata) {
            return new String[] {
                "com.samanvay.shared.security.SecurityConfig", "com.samanvay.shared.security.RealmIssuerStartupCheck"
            };
        }
    }

    @TestConfiguration
    @EnableWebMvc
    @Import({SecurityImports.class, OpsMetricsController.class, OpsMetricsService.class})
    static class Config {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        SlaOverview slaOverview() {
            return asOf -> new SlaOverview.Snapshot(asOf, 0, 0, 0, List.of(), List.of());
        }

        @Bean
        JourneyService journeyService() {
            JourneyService journeys = mock(JourneyService.class);
            when(journeys.openExceptions()).thenReturn(List.of());
            return journeys;
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    @DynamicPropertySource
    static void testRealms(DynamicPropertyRegistry registry) {
        registry.add("samanvay.security.staff.issuer-uri", () -> TestTokens.STAFF_ISSUER);
        registry.add("samanvay.security.staff.public-key-location", () -> TestTokens.STAFF_PUBLIC_KEY_PEM.toUri().toString());
        registry.add("samanvay.security.staff.allowed-clients", () -> String.join(",", TestTokens.STAFF_CLIENTS));
        registry.add("samanvay.security.citizen.issuer-uri", () -> TestTokens.CITIZEN_ISSUER);
        registry.add("samanvay.security.citizen.public-key-location", () -> TestTokens.CITIZEN_PUBLIC_KEY_PEM.toUri().toString());
        registry.add("samanvay.security.citizen.allowed-clients", () -> TestTokens.CITIZEN_UI_CLIENT);
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    MeterRegistry meters;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static String bearer(String token) {
        return TestTokens.bearer(token);
    }

    @Test
    void anonymousCallerGets401() throws Exception {
        mvc.perform(get(ROUTE)).andExpect(status().isUnauthorized());
    }

    @Test
    void forgedAndExpiredStaffTokensGet401() throws Exception {
        mvc.perform(get(ROUTE).header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.forgedOfficer("x"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(ROUTE).header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.expiredOfficer("x"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void officerGetsTheFourDashboardsAsJson() throws Exception {
        OpsMetrics.recordConnectorExchange(meters, "revenue-rest-mock", OpsMetrics.OUTCOME_SUCCESS, TimeUnit.MILLISECONDS.toNanos(30));
        meters.counter(OpsMetrics.CONSENT_AUTHORIZE, "outcome", "denied", "reason", "NO_CONSENT").increment();

        mvc.perform(get(ROUTE).header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.officer("ops-officer"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").value("2026-09-29T10:00:00Z"))
                .andExpect(jsonPath("$.connector.sources[0].source").value("revenue-rest-mock"))
                .andExpect(jsonPath("$.connector.sources[0].success").value(1))
                .andExpect(jsonPath("$.connector.sources[0].latency.p95Ms").isNumber())
                .andExpect(jsonPath("$.sla.open").value(0))
                .andExpect(jsonPath("$.consent.denied").value(1))
                .andExpect(jsonPath("$.consent.denialsByReason[0].reason").value("NO_CONSENT"))
                .andExpect(jsonPath("$.exceptionQueue.byReason").isEmpty())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void adminGetsIt() throws Exception {
        mvc.perform(get(ROUTE).header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.admin("ops-admin"))))
                .andExpect(status().isOk());
    }

    @Test
    void nonStaffOrNonOpsCallersGet403() throws Exception {
        String[] tokens = {
            TestTokens.citizen("some-citizen"),
            TestTokens.reviewer("some-reviewer"),
            TestTokens.department("matrix-dept", "revenue-rest-mock"),
            TestTokens.citizenRealmWithRoles("sneaky-citizen", List.of("admin", "officer"))
        };
        for (String token : tokens) {
            mvc.perform(get(ROUTE).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void thereAreNoWritesUnderOps() throws Exception {
        mvc.perform(post(ROUTE).header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.admin("ops-admin"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/ops/other").header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.citizen("c"))))
                .andExpect(status().isForbidden());
    }

    /** The dashboards must not have opened any HTTP metrics surface: actuator stays off the web. */
    @Test
    void actuatorStaysUnexposedInApplicationConfig() throws IOException {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties props = yaml.getObject();
        assertThat(props).isNotNull();
        assertThat(props.getProperty("management.server.port")).isEqualTo("-1");
        assertThat(props.getProperty("management.endpoints.web.exposure.exclude")).isEqualTo("*");
        assertThat(props.getProperty("management.endpoints.web.exposure.include")).isNull();
        assertThat(props.stringPropertyNames().stream().filter(k -> k.contains("prometheus"))).isEmpty();
    }
}
