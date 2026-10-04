package in.samanvay.departments.kit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PortalSessionTest {

    static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    final PortalSession sessions = new PortalSession("test-secret");
    final PortalSession.Session who = new PortalSession.Session("EDU-1001", UUID.randomUUID(), "Asha | Patil");

    @Test
    void a_session_round_trips_including_odd_characters_in_the_name() {
        assertThat(sessions.read(sessions.issue(who, NOW), NOW.plusSeconds(60))).contains(who);
    }

    @Test
    void a_session_ends_after_eight_hours() {
        String cookie = sessions.issue(who, NOW);
        assertThat(sessions.read(cookie, NOW.plus(PortalSession.SESSION_TTL).minusSeconds(1))).isPresent();
        assertThat(sessions.read(cookie, NOW.plus(PortalSession.SESSION_TTL))).isEmpty();
    }

    @Test
    void a_changed_field_a_wrong_secret_or_a_different_kind_of_token_is_not_accepted() {
        String cookie = sessions.issue(who, NOW);
        String[] parts = cookie.split("\\.");
        parts[2] = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("EDU-9999".getBytes());
        assertThat(sessions.read(String.join(".", parts), NOW)).isEmpty();
        assertThat(new PortalSession("other-secret").read(cookie, NOW)).isEmpty();
        assertThat(sessions.readTicket(cookie, NOW)).isEmpty();
        assertThat(sessions.read(sessions.issueTicket("EDU-1001", NOW), NOW)).isEmpty();
        assertThat(sessions.read(null, NOW)).isEmpty();
        assertThat(sessions.read("garbage", NOW)).isEmpty();
    }

    @Test
    void a_ticket_lives_five_minutes() {
        String ticket = sessions.issueTicket("EDU-1001", NOW);
        assertThat(sessions.readTicket(ticket, NOW.plusSeconds(299))).contains("EDU-1001");
        assertThat(sessions.readTicket(ticket, NOW.plusSeconds(300))).isEmpty();
    }

    @Test
    void without_a_configured_secret_the_secret_is_kept_in_a_file_so_a_restart_does_not_sign_everyone_out() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempDirectory("session").resolve("portal-session.secret");
        String cookie = PortalSession.withSecretFile("", file).issue(who, NOW);

        PortalSession afterRestart = PortalSession.withSecretFile("", file); // a new process reads the same file
        assertThat(afterRestart.read(cookie, NOW.plusSeconds(60))).contains(who);
        assertThat(java.nio.file.Files.size(file)).isGreaterThanOrEqualTo(32);

        java.nio.file.Path other = java.nio.file.Files.createTempDirectory("session").resolve("portal-session.secret");
        assertThat(PortalSession.withSecretFile(null, other).read(cookie, NOW)).as("another server's secret differs").isEmpty();
    }

    @Test
    void a_configured_secret_wins_and_no_file_is_made() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempDirectory("session").resolve("portal-session.secret");
        PortalSession configured = PortalSession.withSecretFile("from-config", file);
        assertThat(new PortalSession("from-config").read(configured.issue(who, NOW), NOW)).contains(who);
        assertThat(java.nio.file.Files.exists(file)).isFalse();
    }
}
