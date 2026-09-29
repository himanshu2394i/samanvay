package com.samanvay.orchestration.internal.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.SamanvayApplication;
import com.samanvay.connector.internal.protocol.DeadlineHttp;
import com.samanvay.connector.internal.protocol.ExchangeDeadlineExceededException;
import com.samanvay.connector.internal.protocol.TricklingDepartment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.samanvay.consent.api.AccessAuthority;
import com.samanvay.consent.api.AccessGrant;
import com.samanvay.consent.api.AccessRequest;
import com.samanvay.consent.api.ConsentUsage;
import com.samanvay.connector.api.Capability;
import com.samanvay.connector.api.ConnectorResult;
import com.samanvay.connector.api.ConnectorRuntime;
import com.samanvay.connector.api.ExecutionInputs;
import com.samanvay.identity.api.CitizenProfiles;
import com.samanvay.identity.api.IdentityLinking;
import com.samanvay.identity.api.ProfileDraft;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.PrincipalRef;
import com.samanvay.shared.PurposeCode;
import com.samanvay.shared.RequesterRef;
import com.samanvay.shared.SubjectRef;
import com.samanvay.shared.test.PostgresIntegrationTest;
import com.samanvay.shared.test.TestHttp;
import com.samanvay.shared.test.TestTokens;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

/**
 * One check per document per application (V189/V190 consent_usage), against real Postgres, the
 * real AccessAuthority and the real usage settlement. Only the department call is a stub, which
 * counts its calls.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsentFrequencyIT extends OneCheckITSupport {

    @Autowired
    com.samanvay.payments.api.DisbursementService disbursements;

    // ---- 1. concurrent checks ----------------------------------------------------------------

    @Test
    void twoConcurrentChecksForTheSameApplicationGiveOneSuccessOneRefusalOneDepartmentCall() throws Exception {
        String app = app();
        CyclicBarrier start = new CyclicBarrier(2);
        // Each side counts down when it reaches the department or is refused; the department holds
        // until both have, so the winner's claim is still PENDING (in flight) while the loser checks.
        CountDownLatch bothDecided = new CountDownLatch(2);
        department.onCall = grant -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("no transaction held across the department call").isFalse();
            assertThat(committedState(grant.id())).as("claim committed before the call").isEqualTo("PENDING");
            bothDecided.countDown();
            await(bothDecided);
            return success();
        };
        long before = headSeq();
        List<String> outcomes = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    String outcome = delegate.execute(fetch(app), inputs());
                    if (!"COMPLETED".equals(outcome)) {
                        bothDecided.countDown();
                    }
                    return outcome;
                }));
            }
            for (Future<String> f : results) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(department.calls.get()).as("department called exactly once").isEqualTo(1);
        assertThat(outcomes).containsExactlyInAnyOrder("COMPLETED", "CHECK_ALREADY_USED");
        List<Map<String, Object>> refusals = jdbc.queryForList(
                "SELECT outcome, reason, meta::text AS meta FROM audit.audit_entry"
                        + " WHERE seq > ? AND subject_id = ? AND outcome = 'DENIED'",
                before, citizen.toString());
        assertThat(refusals).as("exactly one refusal audit row (no GRANT_DENIED as well)").hasSize(1);
        assertThat(audit(before, "CONSENT_FREQUENCY_REFUSED")).hasSize(1);
        assertThat(refusals.get(0).get("reason")).isEqualTo("CHECK_ALREADY_USED");
        assertThat(refusals.get(0).get("meta").toString()).contains(OFFICER_COPY).contains(app);
        assertThat(usageRows(app)).extracting(r -> r.get("state")).containsExactly("USED");
    }

    @Test
    void aSecondCheckAfterSuccessIsRefusedWithTheOfficerCopyButAnotherApplicationMayCheck() {
        String app = app();
        department.onCall = g -> success();
        assertThat(delegate.execute(fetch(app), inputs())).isEqualTo("COMPLETED");
        long before = headSeq();
        var decision = access.authorize(fetch(app));
        assertThat(decision).isInstanceOf(com.samanvay.consent.api.AccessDecision.Denied.class);
        var denied = (com.samanvay.consent.api.AccessDecision.Denied) decision;
        assertThat(denied.reason().name()).isEqualTo("CHECK_ALREADY_USED");
        assertThat(denied.message()).isEqualTo(OFFICER_COPY);
        assertThat(jdbc.queryForList("SELECT action FROM audit.audit_entry WHERE seq > ? AND subject_id = ?",
                        before, citizen.toString()))
                .extracting(r -> r.get("action")).containsExactly("CONSENT_FREQUENCY_REFUSED");
        assertThat(delegate.execute(fetch(app()), inputs())).as("a different application").isEqualTo("COMPLETED");
        assertThat(department.calls.get()).isEqualTo(2);
    }

    @Test
    void oneCheckConsentWithoutAnApplicationIsRefused() {
        department.onCall = g -> success();
        assertThat(delegate.execute(fetch(null), inputs())).isEqualTo("APPLICATION_REQUIRED");
        assertThat(department.calls.get()).isZero();
    }

    @Test
    void aLaterRefusalInTheSameGrantCheckDropsTheClaim() {
        jdbc.update("UPDATE registry_pointer SET valid_until = current_date - 1 WHERE subject_id = ?", citizen);
        String app = app();
        assertThat(delegate.execute(fetch(app), inputs())).isEqualTo("POINTER_EXPIRED");
        assertThat(usageRows(app)).as("no check happened, so no claim is kept").isEmpty();
        assertThat(department.calls.get()).isZero();
    }

    @Test
    void oncePerYearAllowsOneCheckPerDocumentPerYear() {
        jdbc.update("UPDATE consent_artifact SET frequency = 'ONCE_PER_YEAR' WHERE id = ?", consentId);
        department.onCall = g -> success();
        // Scope is the year, not the application: a different application in the same year is still refused.
        assertThat(delegate.execute(fetch(app()), inputs())).isEqualTo("COMPLETED");
        assertThat(delegate.execute(fetch(app()), inputs())).isEqualTo("CHECK_ALREADY_USED");
        assertThat(department.calls.get()).as("the second check never reaches the department").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM consent_usage WHERE consent_id = ? AND scope_key LIKE 'YEAR:%' AND state = 'USED'",
                consentId)).isEqualTo(1);
    }

    @Test
    void oncePerPaymentAllowsOneCheckPerPaymentIdAndScopesByAKeyedHash() {
        jdbc.update("UPDATE consent_artifact SET frequency = 'ONCE_PER_PAYMENT' WHERE id = ?", consentId);
        department.onCall = g -> success();
        String app = app();
        String payment1 = UUID.randomUUID().toString();
        String payment2 = UUID.randomUUID().toString();
        assertThat(delegate.execute(fetch(app, payment1), inputs())).isEqualTo("COMPLETED");
        long before = headSeq();
        assertThat(delegate.execute(fetch(app, payment1), inputs())).as("same payment again").isEqualTo("CHECK_ALREADY_USED");
        assertThat(delegate.execute(fetch(app(), payment1), inputs()))
                .as("the scope is the payment, not the application").isEqualTo("CHECK_ALREADY_USED");
        assertThat(audit(before, "CONSENT_FREQUENCY_REFUSED")).hasSize(2);
        assertThat(delegate.execute(fetch(app, payment2), inputs())).as("a different payment").isEqualTo("COMPLETED");
        assertThat(department.calls.get()).as("refused checks never reach the department").isEqualTo(2);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT scope_key, scope_key_version, state FROM consent_usage WHERE consent_id = ?", consentId);
        assertThat(rows).hasSize(2).allSatisfy(r -> {
            assertThat(r.get("state")).isEqualTo("USED");
            assertThat(r.get("scope_key_version")).as("key version stored beside the hash").isEqualTo("v1");
            assertThat(r.get("scope_key").toString()).matches("PAYMENT:[0-9a-f]{64}");
        });
        assertThat(rows.stream().map(r -> r.get("scope_key").toString()).toList())
                .as("the raw payment id is never the scope key")
                .noneMatch(k -> k.contains(payment1) || k.contains(payment2))
                .doesNotHaveDuplicates();
    }

    @Test
    void theInstalmentIdsOfADisbursementAreThePaymentIdsTheChecksAreScopedBy() {
        jdbc.update("UPDATE consent_artifact SET frequency = 'ONCE_PER_PAYMENT' WHERE id = ?", consentId);
        department.onCall = g -> success();
        var disbursement = disbursements.disburse(UUID.randomUUID(), citizen, "SOME_JOURNEY");
        String first = disbursement.instalments().get(0).id().toString();
        String second = disbursement.instalments().get(1).id().toString();
        String app = disbursement.applicationId().toString();
        assertThat(delegate.execute(fetch(app, first), inputs())).isEqualTo("COMPLETED");
        assertThat(delegate.execute(fetch(app, first), inputs())).as("instalment 1 already checked").isEqualTo("CHECK_ALREADY_USED");
        assertThat(delegate.execute(fetch(app, second), inputs())).as("instalment 2 is its own payment").isEqualTo("COMPLETED");
    }

    @Test
    void oncePerPaymentWithoutAPaymentIdIsRefused() {
        jdbc.update("UPDATE consent_artifact SET frequency = 'ONCE_PER_PAYMENT' WHERE id = ?", consentId);
        department.onCall = g -> success();
        assertThat(delegate.execute(fetch(app(), null), inputs())).isEqualTo("PAYMENT_REQUIRED");
        assertThat(delegate.execute(fetch(app(), "  "), inputs())).as("blank counts as none").isEqualTo("PAYMENT_REQUIRED");
        assertThat(department.calls.get()).isZero();
        assertThat(count("SELECT count(*) FROM consent_usage WHERE consent_id = ?", consentId)).isZero();
    }

    @Test
    void aFailedPaymentCheckIsReleasedSoTheSamePaymentCanBeRetried() {
        jdbc.update("UPDATE consent_artifact SET frequency = 'ONCE_PER_PAYMENT' WHERE id = ?", consentId);
        String payment = UUID.randomUUID().toString();
        department.onCall = g -> new ConnectorResult.Unavailable(com.samanvay.connector.api.FailureKind.REMOTE_FAULT, true);
        assertThat(delegate.execute(fetch(app(), payment), inputs())).isEqualTo("PENDING_SOURCE");
        assertThat(count("SELECT count(*) FROM consent_usage WHERE consent_id = ?", consentId)).as("released").isZero();
        department.onCall = g -> success();
        assertThat(delegate.execute(fetch(app(), payment), inputs())).isEqualTo("COMPLETED");
    }

    // ---- 2. timeout, then retry --------------------------------------------------------------

    @Test
    void timeoutReleasesTheCheckAndTheRetrySucceeds() {
        String app = app();
        long before = headSeq();
        List<UUID> grants = Collections.synchronizedList(new ArrayList<>());
        department.onCall = grant -> {
            grants.add(grant.id());
            if (grants.size() == 1) {
                throw new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out"));
            }
            return success();
        };
        assertThatThrownBy(() -> delegate.executeForResult(fetch(app), inputs()))
                .isInstanceOf(ResourceAccessException.class);
        assertThat(usageRows(app)).as("claim released").isEmpty();
        List<Map<String, Object>> released = audit(before, "CONSENT_CHECK_RELEASED");
        assertThat(released).as("failed attempt audited once").hasSize(1);
        assertThat(released.get(0).get("reason")).isEqualTo("TIMEOUT");
        assertThat(released.get(0).get("grant_id")).isEqualTo(grants.get(0));
        assertThat(released.get(0).get("outcome")).isEqualTo("ERROR");

        assertThat(delegate.executeForResult(fetch(app), inputs())).isInstanceOf(ConnectorResult.Success.class);
        assertThat(department.calls.get()).as("department called twice").isEqualTo(2);
        assertThat(usageRows(app)).singleElement().satisfies(r -> {
            assertThat(r.get("state")).isEqualTo("USED");
            assertThat(r.get("grant_id")).isEqualTo(grants.get(1));
        });
    }

    // ---- 3. 5xx and malformed replies release the check --------------------------------------

    @Test
    void serverErrorReleasesTheCheck() {
        assertReleasedThenRetryable(
                g -> {
                    throw HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", null, null, null);
                },
                HttpServerErrorException.class,
                "REMOTE_FAULT");
    }

    @Test
    void unparseableReplyReleasesTheCheck() {
        assertReleasedThenRetryable(
                g -> {
                    JsonMapper.builder().build().readTree("{\"income\": <html>");
                    return success();
                },
                tools.jackson.core.JacksonException.class,
                "MALFORMED_RESPONSE");
    }

    @Test
    void replyFailingTheOutputSchemaReleasesTheCheck() {
        String app = app();
        long before = headSeq();
        department.onCall = g -> department.calls.get() == 1
                ? new ConnectorResult.Invalid(List.of("$.annualIncome: required"))
                : success();
        assertThat(delegate.executeForResult(fetch(app), inputs())).isInstanceOf(ConnectorResult.Invalid.class);
        assertThat(usageRows(app)).isEmpty();
        assertThat(audit(before, "CONSENT_CHECK_RELEASED")).singleElement()
                .satisfies(r -> assertThat(r.get("reason")).isEqualTo("MALFORMED_RESPONSE"));
        assertThat(delegate.executeForResult(fetch(app), inputs())).isInstanceOf(ConnectorResult.Success.class);
        assertThat(department.calls.get()).isEqualTo(2);
    }

    private void assertReleasedThenRetryable(
            Function<AccessGrant, ConnectorResult> firstCall, Class<? extends Throwable> thrown, String reason) {
        String app = app();
        long before = headSeq();
        department.onCall = g -> department.calls.get() == 1 ? firstCall.apply(g) : success();
        assertThatThrownBy(() -> delegate.executeForResult(fetch(app), inputs())).isInstanceOf(thrown);
        assertThat(usageRows(app)).as("claim released").isEmpty();
        assertThat(audit(before, "CONSENT_CHECK_RELEASED")).singleElement()
                .satisfies(r -> assertThat(r.get("reason")).isEqualTo(reason));
        assertThat(delegate.executeForResult(fetch(app), inputs())).isInstanceOf(ConnectorResult.Success.class);
        assertThat(department.calls.get()).isEqualTo(2);
        assertThat(usageRows(app)).extracting(r -> r.get("state")).containsExactly("USED");
    }

    // ---- 4. stale PENDING claims ---------------------------------------------------------------

    @Test
    void stalePendingClaimIsReclaimedFreshOneIsNotUsedOneNever() {
        long window = connectorTimeout.multipliedBy(2).toSeconds();
        String stale = app();
        String fresh = app();
        String used = app();
        UUID abandoned = UUID.randomUUID();
        insertUsage(stale, abandoned, "PENDING", window + 1);
        insertUsage(fresh, UUID.randomUUID(), "PENDING", window - 1);
        insertUsage(used, UUID.randomUUID(), "USED", 3600);
        department.onCall = g -> success();

        assertThat(delegate.execute(fetch(stale), inputs())).as("older than 2x timeout").isEqualTo("COMPLETED");
        assertThat(usageRows(stale)).singleElement().satisfies(r -> {
            assertThat(r.get("state")).isEqualTo("USED");
            assertThat(r.get("grant_id")).isNotEqualTo(abandoned);
        });
        assertThat(delegate.execute(fetch(fresh), inputs())).as("younger than 2x timeout").isEqualTo("CHECK_ALREADY_USED");
        assertThat(delegate.execute(fetch(used), inputs())).as("USED is never reclaimed").isEqualTo("CHECK_ALREADY_USED");
        assertThat(department.calls.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT meta::text FROM audit.audit_entry WHERE action = 'GRANT_ISSUED'"
                        + " AND subject_id = ? ORDER BY seq DESC LIMIT 1", String.class, citizen.toString()))
                .contains("RECLAIMED");
    }

    @Test
    void twoConcurrentChecksOnAStaleClaimReclaimItOnce() throws Exception {
        String app = app();
        insertUsage(app, UUID.randomUUID(), "PENDING", connectorTimeout.multipliedBy(2).toSeconds() + 5);
        department.onCall = g -> success();
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return delegate.execute(fetch(app), inputs());
                }));
            }
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : results) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsExactlyInAnyOrder("COMPLETED", "CHECK_ALREADY_USED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(department.calls.get()).isEqualTo(1);
    }

    // ---- total deadline: a trickled body is cut off, the claim released, the retry succeeds ----

    @Test
    void bodyTrickledPastTheTotalLimitIsCutOffReleasedAndTheRetrySucceeds() throws Exception {
        long gap = 100; // one byte every 100 ms: a socket read timeout would never fire
        Duration total = Duration.ofMillis(800);
        try (TricklingDepartment dept = new TricklingDepartment(gap)) {
            DeadlineHttp http = new DeadlineHttp(Duration.ofSeconds(5), total);
            JsonMapper json = JsonMapper.builder().build();
            department.onCall = g -> new ConnectorResult.Success(
                    json.readTree(http.get(dept.uri(department.calls.get() == 1 ? "/slow" : "/fast"))), null);
            String app = app();
            long before = headSeq();
            long start = System.nanoTime();
            assertThatThrownBy(() -> delegate.executeForResult(fetch(app), inputs()))
                    .isInstanceOf(ExchangeDeadlineExceededException.class);
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertThat(tookMs).as("cut off at the total limit").isLessThan(total.toMillis() + 1500)
                    .isLessThan(TricklingDepartment.trickleMillis(gap) / 2);
            assertThat(usageRows(app)).as("claim released").isEmpty();
            assertThat(audit(before, "CONSENT_CHECK_RELEASED")).singleElement()
                    .satisfies(r -> assertThat(r.get("reason")).isEqualTo("TIMEOUT"));

            assertThat(delegate.executeForResult(fetch(app), inputs())).isInstanceOf(ConnectorResult.Success.class);
            assertThat(department.calls.get()).isEqualTo(2);
            assertThat(usageRows(app)).extracting(r -> r.get("state")).containsExactly("USED");
            assertThat(dept.trickleEnded.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(dept.trickleCompleted).as("the late body was never fully sent").isFalse();
        }
    }

    // ---- the fetch path: authorize() is the outermost transaction ----------------------------

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void authorizeRunsOutermostAndTheDenialSurvivesAFailingFetchPath() {
        jdbc.update("UPDATE consent_artifact SET status = 'REVOKED', revoked_at = now() WHERE id = ?", consentId);
        java.util.concurrent.atomic.AtomicBoolean outerTx = new java.util.concurrent.atomic.AtomicBoolean(true);
        FetchDataDelegate probed = new FetchDataDelegate(req -> {
            outerTx.set(TransactionSynchronizationManager.isActualTransactionActive());
            return access.authorize(req);
        }, department, usage);
        long before = headSeq();
        assertThatThrownBy(() -> {
                    assertThat(probed.execute(fetch(app()), inputs())).isEqualTo("CONSENT_REVOKED");
                    throw new IllegalStateException("the fetch path fails after the refusal");
                })
                .hasMessageContaining("fails after the refusal");
        assertThat(outerTx).as("no transaction around authorize(): it is the outermost").isFalse();
        assertThat(audit(before, "GRANT_DENIED")).as("the refusal row is committed and stays").singleElement()
                .satisfies(r -> assertThat(r.get("reason")).isEqualTo("CONSENT_REVOKED"));
        assertThat(department.calls.get()).isZero();
    }

    @Test
    void aFetchInsideACallerTransactionIsRefusedBeforeAnythingIsWritten() {
        jdbc.update("UPDATE consent_artifact SET status = 'REVOKED', revoked_at = now() WHERE id = ?", consentId);
        long before = headSeq();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThatThrownBy(() -> delegate.execute(fetch(app()), inputs()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("outside a transaction");
            status.setRollbackOnly(); // the caller's work fails and rolls back
        });
        assertThat(audit(before, "GRANT_DENIED"))
                .as("nothing was written inside the caller's (rolled back) transaction")
                .isEmpty();
        assertThat(delegate.execute(fetch(app()), inputs())).as("outside a transaction").isEqualTo("CONSENT_REVOKED");
        assertThat(audit(before, "GRANT_DENIED")).hasSize(1);
    }

    @Test
    void grantedFetchThatFailsKeepsItsGrantAndReleaseRows() {
        department.onCall = g -> {
            throw HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "down", null, null, null);
        };
        long before = headSeq();
        assertThatThrownBy(() -> delegate.execute(fetch(app()), inputs())).isInstanceOf(HttpServerErrorException.class);
        assertThat(audit(before, "GRANT_ISSUED")).hasSize(1);
        assertThat(audit(before, "CONSENT_CHECK_RELEASED")).hasSize(1);
    }

    // ---- V190 CHECK constraint on consent_artifact.frequency ---------------------------------

    @Test
    void misspelledConsentFrequencyIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE consent_artifact SET frequency = 'ONCE_PER_DOCUMENT_PER_APPLICTION' WHERE id = ?",
                        consentId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("consent_artifact_frequency_known");
        assertThat(jdbc.queryForObject("SELECT frequency FROM consent_artifact WHERE id = ?", String.class, consentId))
                .isEqualTo("ONCE_PER_DOCUMENT_PER_APPLICATION");
    }

    // ---- 5. the constraint -------------------------------------------------------------------

    @Test
    void uniqueConstraintExistsInTheCatalog() throws Exception {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT con.contype::text AS type, att.attname AS col
                  FROM pg_constraint con
                  JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord) ON true
                  JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = k.attnum
                 WHERE con.conrelid = 'consent_usage'::regclass AND con.conname = 'consent_usage_one_check'
                 ORDER BY k.ord
                """);
        assertThat(rows).extracting(r -> r.get("type")).containsOnly("u");
        assertThat(rows).extracting(r -> r.get("col")).containsExactly("consent_id", "document_type", "scope_key");
        assertThat(Files.readString(Path.of("src/main/resources/db/migration/V189__consent_usage_one_check.sql")))
                .contains("CONSTRAINT consent_usage_one_check UNIQUE (consent_id, document_type, application_id)");
        assertThat(Files.readString(Path.of("src/main/resources/db/migration/V190__frequency_values_and_claim_token.sql")))
                .contains("RENAME COLUMN application_id TO scope_key");
    }

}
