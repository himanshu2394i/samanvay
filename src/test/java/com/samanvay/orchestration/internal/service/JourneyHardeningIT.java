package com.samanvay.orchestration.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.samanvay.SamanvayApplication;
import com.samanvay.consent.api.AccessRequest;
import com.samanvay.consent.api.AuthProof;
import com.samanvay.consent.api.ConsentRequestDraft;
import com.samanvay.consent.api.ConsentService;
import com.samanvay.identity.api.CitizenProfiles;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.identity.api.ProfileDraft;
import com.samanvay.orchestration.api.JourneyConflictException;
import com.samanvay.orchestration.api.JourneyInstance;
import com.samanvay.orchestration.api.JourneyService;
import com.samanvay.orchestration.api.JourneyState;
import com.samanvay.orchestration.internal.workflow.FetchDataDelegate;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestPrincipals;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * Journey start and retry when things go wrong: an adapter that throws, a retry racing a terminal state,
 * a second start, and the instance state the staff pages read. Real Postgres; the fetch delegate is a spy so
 * a chosen category can blow up with a plain RuntimeException (what a timeout, a 5xx, bad JSON or a mapping NPE
 * looks like by the time it reaches orchestration).
 */
@SpringBootTest(classes = SamanvayApplication.class)
@ActiveProfiles("demo")
class JourneyHardeningIT extends PostgresIntegrationTest {

    static final String JOURNEY = "POST_MATRIC_SCHOLARSHIP";
    static final List<String> CATEGORIES = List.of("INCOME_CERTIFICATE", "CASTE_CERTIFICATE", "MARKS", "BANK_ACCOUNT");

    @Autowired
    CitizenProfiles profiles;

    @Autowired
    IdentityLinking linking;

    @Autowired
    ConsentService consents;

    @Autowired
    JourneyService journeys;

    @Autowired
    ApplicationApprovalService approvals;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    FetchDataDelegate fetch;

    final Set<String> throwing = ConcurrentHashMap.newKeySet();
    final Map<String, Integer> stepRowsSeenAtFetch = new ConcurrentHashMap<>();

    @BeforeEach
    void spyOnFetch() {
        doAnswer(inv -> {
                    AccessRequest request = inv.getArgument(0);
                    stepRowsSeenAtFetch.put(request.category().code(), stepRows(UUID.fromString(request.applicationId())).size());
                    if (throwing.contains(request.category().code())) {
                        throw new IllegalStateException("adapter blew up: " + request.category().code());
                    }
                    return inv.callRealMethod();
                })
                .when(fetch)
                .executeForResult(any(), any());
    }

    @AfterEach
    void removeDraftCopy() {
        jdbc.update("DELETE FROM catalog_journey WHERE code = 'DRAFT_COPY_IT'");
    }

    @Test
    void an_adapter_exception_leaves_pending_steps_and_an_open_exception_not_an_orphan_with_a_500() {
        UUID citizen = citizen();
        throwing.add("MARKS");

        JourneyInstance started = start(citizen);

        assertThat(status(started.id())).isEqualTo("PARTIALLY_VERIFIED");
        assertThat(stepRows(started.id()))
                .containsEntry("MARKS", "PENDING_SOURCE")
                .containsEntry("INCOME_CERTIFICATE", "COMPLETED")
                .containsEntry("CASTE_CERTIFICATE", "COMPLETED")
                .containsEntry("BANK_ACCOUNT", "COMPLETED");
        assertThat(openExceptions(started.id(), "MARKS")).isEqualTo(1);
    }

    @Test
    void every_required_category_has_a_step_row_before_any_fetch_runs() {
        UUID citizen = citizen();
        throwing.addAll(CATEGORIES);

        JourneyInstance started = start(citizen);

        assertThat(stepRowsSeenAtFetch).containsOnlyKeys(CATEGORIES).allSatisfy((category, rows) -> assertThat(rows).isEqualTo(4));
        assertThat(stepRows(started.id())).hasSize(4).allSatisfy((category, status) -> assertThat(status).isEqualTo("PENDING_SOURCE"));
        assertThat(status(started.id())).isEqualTo("PARTIALLY_VERIFIED");
    }

    @Test
    void a_retry_that_fails_again_reuses_the_open_exception_and_counts_the_attempt() {
        UUID citizen = citizen();
        throwing.add("MARKS");
        JourneyInstance started = start(citizen);

        journeys.retryPending(started.id(), TestPrincipals.OFFICER);
        journeys.retryPending(started.id(), TestPrincipals.OFFICER);

        assertThat(openExceptions(started.id(), "MARKS")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT attempt_count FROM orchestration_step_state WHERE instance_id = ? AND step_code = 'MARKS'",
                        Integer.class,
                        started.id()))
                .isEqualTo(3);
        assertThat(status(started.id())).isEqualTo("PARTIALLY_VERIFIED");

        throwing.clear();
        journeys.retryPending(started.id(), TestPrincipals.OFFICER);

        assertThat(status(started.id())).isEqualTo("VERIFIED");
        assertThat(openExceptions(started.id(), "MARKS")).isZero();
        assertThat(stepRows(started.id())).containsEntry("MARKS", "COMPLETED");
    }

    @Test
    void a_retry_fetches_a_required_category_that_has_no_step_row_and_never_verifies_around_it() {
        UUID citizen = citizen();
        throwing.add("MARKS");
        JourneyInstance started = start(citizen);
        jdbc.update("DELETE FROM orchestration_step_state WHERE instance_id = ? AND step_code = 'MARKS'", started.id());
        // the three completed rows alone would say "all done"; the missing category must still hold VERIFIED back
        jdbc.update("UPDATE orchestration_instance SET status = 'PARTIALLY_VERIFIED' WHERE id = ?", started.id());

        throwing.clear();
        journeys.retryPending(started.id(), TestPrincipals.OFFICER);

        assertThat(stepRows(started.id())).containsEntry("MARKS", "COMPLETED");
        assertThat(status(started.id())).isEqualTo("VERIFIED");
    }

    @Test
    void a_late_retry_cannot_walk_a_rejected_application_back_to_verified() throws InterruptedException {
        UUID citizen = citizen();
        throwing.add("MARKS");
        JourneyInstance started = start(citizen);
        approvals.reject(started.id(), "officer-1", "documents forged");
        assertThat(status(started.id())).isEqualTo("REJECTED");
        assertThat(openExceptionsFor(started.id())).as("rejecting closes the queue entries").isZero();
        awaitTracking(started.id(), "REJECTED");

        throwing.clear();
        journeys.retryPending(started.id(), TestPrincipals.OFFICER);
        Thread.sleep(600);

        assertThat(status(started.id())).isEqualTo("REJECTED");
        assertThat(trackingStatus(started.id())).isEqualTo("REJECTED");
        assertThat(openExceptionsFor(started.id())).isZero();
    }

    @Test
    void cancel_refuses_an_approved_application_and_closes_an_open_one() {
        UUID citizen = citizen();
        JourneyInstance approved = start(citizen);
        assertThat(status(approved.id())).isEqualTo("VERIFIED");
        approvals.approve(approved.id(), "officer-1");

        assertThatThrownBy(() -> journeys.cancel(approved.id(), "changed my mind")).isInstanceOf(JourneyConflictException.class);
        assertThat(status(approved.id())).isEqualTo("APPROVED");

        UUID other = citizen();
        JourneyInstance open = start(other);
        journeys.cancel(open.id(), "changed my mind");
        assertThat(status(open.id())).isEqualTo("CLOSED");
    }

    @Test
    void a_second_start_while_one_is_open_is_a_conflict_and_a_closed_one_frees_the_slot() {
        UUID citizen = citizen();
        JourneyInstance first = start(citizen);

        assertThatThrownBy(() -> start(citizen))
                .isInstanceOf(JourneyConflictException.class)
                .satisfies(e -> assertThat(((JourneyConflictException) e).status()).isEqualTo(409));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM orchestration_instance WHERE citizen_id = ?", Integer.class, citizen))
                .isEqualTo(1);

        journeys.cancel(first.id(), "withdrawn");
        assertThat(start(citizen).id()).isNotEqualTo(first.id());
    }

    @Test
    void a_journey_that_is_not_published_cannot_be_started() {
        UUID citizen = citizen();
        jdbc.update(
                "INSERT INTO catalog_journey (code, name, bpmn_ref, required_categories, policy, status)"
                        + " SELECT 'DRAFT_COPY_IT', name, bpmn_ref, required_categories, policy, 'DRAFT'"
                        + " FROM catalog_journey WHERE code = ?",
                JOURNEY);

        assertThatThrownBy(() -> journeys.start("DRAFT_COPY_IT", citizen, JsonMapper.builder().build().createObjectNode(), TestPrincipals.OFFICER))
                .isInstanceOf(JourneyConflictException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM orchestration_instance WHERE citizen_id = ?", Integer.class, citizen))
                .isZero();
    }

    @Test
    void state_reports_the_real_status_and_each_categorys_step_status() {
        UUID citizen = citizen();
        throwing.add("MARKS");
        JourneyInstance started = start(citizen);

        JourneyState state = journeys.state(started.id());

        assertThat(state.status()).isEqualTo("PARTIALLY_VERIFIED");
        assertThat(state.stepOutcomes())
                .containsEntry("MARKS", "PENDING_SOURCE")
                .containsEntry("INCOME_CERTIFICATE", "COMPLETED")
                .containsEntry("CASTE_CERTIFICATE", "COMPLETED")
                .containsEntry("BANK_ACCOUNT", "COMPLETED");
    }

    // ---- helpers

    private JourneyInstance start(UUID citizen) {
        return journeys.start(JOURNEY, citizen, JsonMapper.builder().build().createObjectNode(), TestPrincipals.OFFICER);
    }

    private UUID citizen() {
        UUID citizen = profiles.register(new ProfileDraft(
                "Ramesh Kumar", "रमेश", "Ramesh", "Kumar", "Suresh", LocalDate.of(2004, 1, 15), "DAY", "M", "99****21"));
        String suffix = Long.toHexString(System.nanoTime());
        linking.assertLink(citizen, "REVENUE", "RATION", "RC-hd-" + suffix, com.samanvay.identity.api.AuthProof.localIdOtpDemo());
        linking.assertLink(citizen, "EDUCATION", "STUDENT", "STU-hd-" + suffix, com.samanvay.identity.api.AuthProof.localIdOtpDemo());
        linking.assertLink(citizen, "DBT", "DBT", "DBT-hd-" + suffix, com.samanvay.identity.api.AuthProof.localIdOtpDemo());
        var request = consents.request(new ConsentRequestDraft(citizen, "SCHOLARSHIP", "SCHOLARSHIP_ELIGIBILITY"));
        consents.grant(request.id(), citizen, new AuthProof("session-jti"));
        return citizen;
    }

    private String status(UUID instance) {
        return jdbc.queryForObject("SELECT status FROM orchestration_instance WHERE id = ?", String.class, instance);
    }

    private Map<String, String> stepRows(UUID instance) {
        Map<String, String> rows = new java.util.HashMap<>();
        jdbc.query(
                "SELECT step_code, status FROM orchestration_step_state WHERE instance_id = ?",
                rs -> {
                    rows.put(rs.getString("step_code"), rs.getString("status"));
                },
                instance);
        return rows;
    }

    private int openExceptions(UUID instance, String step) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM orchestration_exception WHERE instance_id = ? AND step_code = ? AND status = 'OPEN'",
                Integer.class,
                instance,
                step);
    }

    private int openExceptionsFor(UUID instance) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM orchestration_exception WHERE instance_id = ? AND status = 'OPEN'", Integer.class, instance);
    }

    private String trackingStatus(UUID instance) {
        List<String> s = jdbc.queryForList("SELECT status FROM tracking_application WHERE id = ?", String.class, instance);
        return s.isEmpty() ? null : s.getFirst();
    }

    private void awaitTracking(UUID instance, String expected) throws InterruptedException {
        for (int i = 0; i < 100 && !expected.equals(trackingStatus(instance)); i++) {
            Thread.sleep(100);
        }
        assertThat(trackingStatus(instance)).isEqualTo(expected);
    }
}
