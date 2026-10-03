package in.samanvay.departments.revenue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The ticket that carries "this person passed the password step" to the one-time-code step. It is stateless (signed, not
 * stored), short-lived, and tied to the exact state and nonce of the login it was issued for.
 */
class LoginTicketTest {

    static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    final LoginTicket tickets = new LoginTicket(Duration.ofMinutes(5));

    @Test
    void a_ticket_returns_the_person_it_was_issued_for() {
        String t = tickets.issue("RV-1001", "state-1", "nonce-1", NOW);
        assertThat(tickets.verify(t, "state-1", "nonce-1", NOW.plusSeconds(60))).contains("RV-1001");
    }

    @Test
    void it_is_refused_for_another_state_or_nonce() {
        String t = tickets.issue("RV-1001", "state-1", "nonce-1", NOW);
        assertThat(tickets.verify(t, "state-2", "nonce-1", NOW)).isEmpty();
        assertThat(tickets.verify(t, "state-1", "nonce-2", NOW)).isEmpty();
    }

    @Test
    void it_expires() {
        String t = tickets.issue("RV-1001", "s", "n", NOW);
        assertThat(tickets.verify(t, "s", "n", NOW.plus(Duration.ofMinutes(5)).minusSeconds(1))).isPresent();
        assertThat(tickets.verify(t, "s", "n", NOW.plus(Duration.ofMinutes(5)).plusSeconds(1))).isEmpty();
    }

    @Test
    void a_changed_person_or_a_forged_signature_is_refused() {
        String t = tickets.issue("RV-1001", "s", "n", NOW);
        String[] parts = t.split("\\.");
        String otherPerson = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("RV-1002|1791111111".getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "." + parts[1];
        assertThat(tickets.verify(otherPerson, "s", "n", NOW)).isEmpty();
        assertThat(tickets.verify(parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA", "s", "n", NOW)).isEmpty();
    }

    @Test
    void a_ticket_from_another_service_instance_is_refused_because_each_has_its_own_secret() {
        String t = new LoginTicket(Duration.ofMinutes(5)).issue("RV-1001", "s", "n", NOW);
        assertThat(tickets.verify(t, "s", "n", NOW)).isEmpty();
    }

    @Test
    void garbage_is_refused_without_an_exception() {
        assertThat(tickets.verify(null, "s", "n", NOW)).isEmpty();
        assertThat(tickets.verify("", "s", "n", NOW)).isEmpty();
        assertThat(tickets.verify("no-dot", "s", "n", NOW)).isEmpty();
        assertThat(tickets.verify("a.b.c", "s", "n", NOW)).isEmpty();
        assertThat(tickets.verify("!!!.???", "s", "n", NOW)).isEmpty();
    }
}
