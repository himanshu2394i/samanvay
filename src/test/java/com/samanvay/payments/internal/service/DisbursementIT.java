package com.samanvay.payments.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.SamanvayApplication;
import com.samanvay.orchestration.api.ApplicationStateChanged;
import com.samanvay.payments.api.Disbursement;
import com.samanvay.payments.api.DisbursementService;
import com.samanvay.payments.api.Instalment;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** The disbursement flow against real Postgres: instalment ids, idempotency, audit, the approval trigger. */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DisbursementIT extends PostgresIntegrationTest {

    @Autowired
    DisbursementService disbursements;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate tx;

    @Test
    void issuesInstalmentsWithDistinctIdsAndAuditsOnce() {
        UUID app = UUID.randomUUID();
        UUID citizen = UUID.randomUUID();
        long before = headSeq();

        Disbursement d = disbursements.disburse(app, citizen, "SOME_JOURNEY");

        assertThat(d.applicationId()).isEqualTo(app);
        assertThat(d.citizenId()).isEqualTo(citizen);
        assertThat(d.status()).isEqualTo("ISSUED");
        assertThat(d.instalments()).extracting(Instalment::sequence).containsExactly(1, 2);
        assertThat(d.instalments()).extracting(Instalment::id).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(d.instalments()).extracting(Instalment::status).containsOnly("SCHEDULED");
        assertThat(disbursements.forApplication(app)).contains(d);
        assertThat(disbursements.instalment(d.instalments().get(1).id())).contains(d.instalments().get(1));
        assertThat(disbursements.instalment(UUID.randomUUID())).isEmpty();

        List<Map<String, Object>> audit = jdbc.queryForList(
                "SELECT actor_type, actor_id, resource, outcome, meta::text AS meta FROM audit.audit_entry"
                        + " WHERE seq > ? AND action = 'DISBURSEMENT_ISSUED' AND subject_id = ?",
                before, citizen.toString());
        assertThat(audit).as("one audit entry").hasSize(1);
        assertThat(audit.get(0).get("actor_type")).isEqualTo("SYSTEM");
        assertThat(audit.get(0).get("resource")).isEqualTo("disbursement");
        assertThat(audit.get(0).get("outcome")).isEqualTo("ALLOWED");
        assertThat(audit.get(0).get("meta").toString())
                .contains(d.id().toString(), app.toString(), d.instalments().get(0).id().toString(),
                        d.instalments().get(1).id().toString());
    }

    @Test
    void issuingAgainIsIdempotentAndWritesNothing() {
        UUID app = UUID.randomUUID();
        UUID citizen = UUID.randomUUID();
        Disbursement first = disbursements.disburse(app, citizen, "SOME_JOURNEY");
        long afterFirst = headSeq();

        Disbursement second = disbursements.disburse(app, citizen, "SOME_JOURNEY");

        assertThat(second).isEqualTo(first);
        assertThat(headSeq()).as("no second audit entry").isEqualTo(afterFirst);
        assertThat(count("SELECT count(*) FROM payments_disbursement WHERE application_id = ?", app)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payments_instalment WHERE disbursement_id = ?", first.id())).isEqualTo(2);
    }

    @Test
    void racingIssuesGiveOneDisbursement() throws Exception {
        UUID app = UUID.randomUUID();
        UUID citizen = UUID.randomUUID();
        CyclicBarrier start = new CyclicBarrier(4);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<UUID> ids = new ArrayList<>();
        try {
            List<Future<Disbursement>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return disbursements.disburse(app, citizen, "SOME_JOURNEY");
                }));
            }
            for (Future<Disbursement> f : results) {
                ids.add(f.get(30, TimeUnit.SECONDS).id());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(ids).as("every caller sees the same disbursement").containsOnly(ids.get(0));
        assertThat(count("SELECT count(*) FROM payments_disbursement WHERE application_id = ?", app)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payments_instalment WHERE disbursement_id = ?", ids.get(0))).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'DISBURSEMENT_ISSUED' AND meta::text LIKE ?",
                        "%" + app + "%"))
                .isEqualTo(1);
    }

    @Test
    void anApprovedApplicationGetsItsDisbursementAndOtherStatusesDoNot() throws Exception {
        UUID approved = trackedApplication();
        UUID verified = trackedApplication();
        tx.executeWithoutResult(s -> {
            events.publishEvent(new ApplicationStateChanged(verified, "VERIFIED"));
            events.publishEvent(new ApplicationStateChanged(approved, "APPROVED"));
            // A redelivery (at-least-once) must not issue a second one.
            events.publishEvent(new ApplicationStateChanged(approved, "APPROVED"));
        });
        Optional<Disbursement> d = awaitDisbursement(approved);
        assertThat(d).isPresent();
        assertThat(d.get().journeyCode()).isEqualTo("SOME_JOURNEY");
        assertThat(d.get().instalments()).hasSize(2);
        Thread.sleep(500);
        assertThat(count("SELECT count(*) FROM payments_disbursement WHERE application_id = ?", approved)).isEqualTo(1);
        assertThat(disbursements.forApplication(verified)).as("not approved, nothing disbursed").isEmpty();
    }

    private UUID trackedApplication() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO tracking_application (id, reference_no, citizen_id, journey_code, process_instance_id, status)"
                        + " VALUES (?, ?, ?, 'SOME_JOURNEY', 'pi-1', 'VERIFIED')",
                id, "MH-PAY-" + id.toString().substring(0, 20), UUID.randomUUID());
        return id;
    }

    private Optional<Disbursement> awaitDisbursement(UUID app) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Optional<Disbursement> d = disbursements.forApplication(app);
            if (d.isPresent()) {
                return d;
            }
            Thread.sleep(100);
        }
        return Optional.empty();
    }

    private long headSeq() {
        return jdbc.queryForObject("SELECT COALESCE(max(seq), 0) FROM audit.audit_entry", Long.class);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
