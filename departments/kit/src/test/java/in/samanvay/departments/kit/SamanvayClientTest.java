package in.samanvay.departments.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SamanvayClientTest {

    FakeSamanvay samanvay;
    SamanvayClient client;

    @BeforeEach
    void start() throws Exception {
        samanvay = new FakeSamanvay();
        client = new SamanvayClient(new PortalProperties.Samanvay(samanvay.url(), samanvay.url() + "/token", "dept-x", "secret"), Clock.systemUTC());
    }

    @AfterEach
    void stop() {
        samanvay.close();
    }

    @Test
    void a_401_evicts_the_cached_token_and_the_call_is_retried_once_with_a_fresh_one() {
        samanvay.on("GET", "/api/applications/MH-1", c -> "Bearer tok-1".equals(c.authorization())
                ? new FakeSamanvay.Reply(401, "{\"detail\":\"expired\"}") : new FakeSamanvay.Reply(200, "{\"referenceNo\":\"MH-1\"}"));
        assertThat(client.application("MH-1")).containsEntry("referenceNo", "MH-1");
        assertThat(samanvay.tokenRequests).isEqualTo(2);
    }

    @Test
    void a_401_that_persists_is_retried_only_once() {
        samanvay.on("GET", "/api/applications/MH-1", 401, "{\"detail\":\"no\"}");
        assertThatThrownBy(() -> client.application("MH-1")).isInstanceOfSatisfying(SamanvayException.class, e -> assertThat(e.status()).isEqualTo(401));
        assertThat(samanvay.callsTo("/api/applications/MH-1")).hasSize(2);
        assertThat(samanvay.tokenRequests).isEqualTo(2);
    }

    @Test
    void a_token_reply_without_an_access_token_is_an_outage_not_a_null_pointer() {
        samanvay.token = n -> new FakeSamanvay.Reply(200, "{\"expires_in\":300}");
        assertThatThrownBy(() -> client.application("MH-1")).isInstanceOfSatisfying(SamanvayException.class, e -> assertThat(e.status()).isEqualTo(503));
        samanvay.token = n -> new FakeSamanvay.Reply(200, "{\"access_token\":\"  \",\"expires_in\":300}");
        assertThatThrownBy(() -> client.application("MH-1")).isInstanceOfSatisfying(SamanvayException.class, e -> assertThat(e.status()).isEqualTo(503));
        assertThat(samanvay.calls).as("no call is made with a missing token").isEmpty();
    }

    @Test
    void a_slow_token_endpoint_does_not_stop_other_callers_from_trying() throws Exception {
        samanvay.tokenDelayMillis = 1500;
        samanvay.on("GET", "/api/applications/MH-1", 200, "{}");
        Thread a = new Thread(() -> client.application("MH-1"));
        Thread b = new Thread(() -> client.application("MH-1"));
        a.start();
        Thread.sleep(150);
        b.start();
        Thread.sleep(700);
        assertThat(samanvay.tokenRequests).as("the second caller reached the token endpoint while the first was still waiting").isEqualTo(2);
        a.join();
        b.join();
    }

    @Test
    void a_failed_call_logs_the_path_but_never_a_citizen_id() {
        // the token endpoint works, the department API address does not answer: the failure text must not carry the citizen ID
        SamanvayClient client = new SamanvayClient(new PortalProperties.Samanvay("http://127.0.0.1:1", samanvay.url() + "/token", "dept-x", "secret"), Clock.systemUTC());
        Logger logger = (Logger) LoggerFactory.getLogger(SamanvayClient.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        UUID citizen = UUID.randomUUID();
        try {
            assertThatThrownBy(() -> client.readiness("DEMO_SERVICE", citizen)).isInstanceOf(SamanvayException.class);
            assertThatThrownBy(() -> client.applications(citizen)).isInstanceOf(SamanvayException.class);
        } finally {
            logger.detachAppender(logs);
        }
        assertThat(logs.list).isNotEmpty();
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList()).noneMatch(m -> m.contains(citizen.toString()));
    }

    @Test
    void an_application_reference_and_a_journey_code_must_look_like_what_they_are() {
        AtomicInteger calls = new AtomicInteger();
        samanvay.on("GET", "/api/applications/x", c -> {
            calls.incrementAndGet();
            return new FakeSamanvay.Reply(200, "{}");
        });
        for (String bad : List.of("..", ".", "a/b", "a?x=1", "a b", "a#b", "%2e%2e", "", "a\n", "a%2Fb")) {
            assertThatThrownBy(() -> client.application(bad)).as(bad).isInstanceOfSatisfying(SamanvayException.class, e -> assertThat(e.status()).isEqualTo(404));
            assertThatThrownBy(() -> client.steps(bad)).as(bad).isInstanceOf(SamanvayException.class);
            assertThatThrownBy(() -> client.issuedRecords(bad)).as(bad).isInstanceOf(SamanvayException.class);
        }
        assertThatThrownBy(() -> client.readiness("../admin", UUID.randomUUID())).isInstanceOf(SamanvayException.class);
        assertThat(samanvay.calls).isEmpty();
        samanvay.on("GET", "/api/applications/MH-2026.A_1@x", 200, "{\"ok\":true}");
        assertThat(client.application("MH-2026.A_1@x")).containsEntry("ok", true);
    }

    @Test
    void the_token_call_has_a_short_timeout_so_a_hung_identity_provider_cannot_hang_citizens() {
        assertThat(SamanvayClient.TOKEN_TIMEOUT).isLessThanOrEqualTo(java.time.Duration.ofSeconds(15));
    }
}
