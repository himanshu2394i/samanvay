package com.samanvay.orchestration.internal.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.SamanvayApplication;
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
 * One check per document per application (V189 consent_usage), against real Postgres, the real
 * AccessAuthority and the real usage settlement. Only the department call is a stub, which
 * counts its calls.
 */
@SpringBootTest(classes = SamanvayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsentFrequencyIT extends PostgresIntegrationTest {

    static final String OFFICER_COPY = "This document has already been checked for this application. "
            + "The citizen's permission allows one check.";
    static final DataCategory DOC = DataCategory.INCOME_CERTIFICATE;

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CitizenProfiles profiles;

    @Autowired
    IdentityLinking linking;

    @Autowired
    AccessAuthority access;

    @Autowired
    ConsentUsage usage;

    @Value("${samanvay.connector.timeout}")
    Duration connectorTimeout;

    CountingDepartment department;
    FetchDataDelegate delegate;
    UUID citizen;
    UUID consentId;

    @BeforeEach
    void citizenWithOneCheckConsent() throws InterruptedException {
        department = new CountingDepartment();
        delegate = new FetchDataDelegate(access, department, usage);
        String subject = "cit-p2f-" + UUID.randomUUID();
        citizen = profiles.registerSelf(new ProfileDraft(
                "Sunita Pawar", "सुनीता", "Sunita", "Pawar", "Ramesh",
                LocalDate.of(2004, 6, 1), "DAY", "F", "98****11"), subject);
        linking.assertLink(citizen, "REVENUE", "RATION", "RC-p2f-" + Long.toHexString(System.nanoTime()),
                com.samanvay.identity.api.AuthProof.digiLockerSandbox());
        var http = TestHttp.as(TestTokens.citizen(subject));
        Map<?, ?> created = http.post().uri(url("/api/consent/requests")).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("citizenId", citizen, "purposeCode", "SCH_ELIGIBILITY_CHECK"))
                .retrieve().body(Map.class);
        Map<?, ?> artifact = http.post().uri(url("/api/consent/requests/" + created.get("id") + "/grant"))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("citizenId", citizen))
                .retrieve().body(Map.class);
        consentId = UUID.fromString(artifact.get("id").toString());
        assertThat(jdbc.queryForObject("SELECT frequency FROM consent_artifact WHERE id = ?", String.class, consentId))
                .as("frequency copied from the catalog purpose at grant time")
                .isEqualTo("ONCE_PER_DOCUMENT_PER_APPLICATION");
        awaitPointer();
    }

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
    void otherFrequenciesAreNotLimitedPerApplication() {
        jdbc.update("UPDATE consent_artifact SET frequency = 'ONCE_PER_YEAR' WHERE id = ?", consentId);
        department.onCall = g -> success();
        String app = app();
        assertThat(delegate.execute(fetch(app), inputs())).isEqualTo("COMPLETED");
        assertThat(delegate.execute(fetch(app), inputs())).isEqualTo("COMPLETED");
        assertThat(department.calls.get()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM consent_usage WHERE consent_id = ?", consentId)).isZero();
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
        assertThat(rows).extracting(r -> r.get("col")).containsExactly("consent_id", "document_type", "application_id");
        assertThat(Files.readString(Path.of("src/main/resources/db/migration/V189__consent_usage_one_check.sql")))
                .contains("CONSTRAINT consent_usage_one_check UNIQUE (consent_id, document_type, application_id)");
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** The department: counts calls and answers as told. */
    static final class CountingDepartment implements ConnectorRuntime {
        final AtomicInteger calls = new AtomicInteger();
        volatile Function<AccessGrant, ConnectorResult> onCall = g -> success();

        @Override
        public ConnectorResult execute(AccessGrant grant, Capability capability, ExecutionInputs inputs) {
            calls.incrementAndGet();
            return onCall.apply(grant);
        }
    }

    static ConnectorResult success() {
        return new ConnectorResult.Success(JsonMapper.builder().build().createObjectNode().put("ok", true), null);
    }

    private AccessRequest fetch(String applicationId) {
        return new AccessRequest(
                new SubjectRef(citizen),
                new RequesterRef("SCHOLARSHIP"),
                DOC,
                "REVENUE",
                "rev-income@1",
                PurposeCode.of("SCH_ELIGIBILITY_CHECK"),
                null,
                new PrincipalRef(PrincipalRef.Kind.OFFICER, "officer-p2-freq"),
                applicationId);
    }

    private static ExecutionInputs inputs() {
        return new ExecutionInputs(DOC, "wf-p2f", Map.of(), Map.of(), Map.of());
    }

    private static String app() {
        return "app-p2f-" + UUID.randomUUID();
    }

    private void insertUsage(String app, UUID grantId, String state, long ageSeconds) {
        jdbc.update("INSERT INTO consent_usage (id, consent_id, document_type, application_id, grant_id, state,"
                        + " claimed_at, used_at) VALUES (?, ?, ?, ?, ?, ?, now() - make_interval(secs => ?),"
                        + " CASE WHEN ? = 'USED' THEN now() END)",
                UUID.randomUUID(), consentId, DOC.code(), app, grantId, state, (double) ageSeconds, state);
    }

    private List<Map<String, Object>> usageRows(String app) {
        return jdbc.queryForList(
                "SELECT state, grant_id, claimed_at FROM consent_usage WHERE consent_id = ? AND application_id = ?",
                consentId, app);
    }

    /** Read on its own autocommit connection: sees only committed rows. */
    private String committedState(UUID grantId) {
        List<String> s = jdbc.queryForList("SELECT state FROM consent_usage WHERE grant_id = ?", String.class, grantId);
        return s.isEmpty() ? null : s.get(0);
    }

    private List<Map<String, Object>> audit(long afterSeq, String action) {
        return jdbc.queryForList(
                "SELECT outcome, reason, grant_id, meta::text AS meta FROM audit.audit_entry"
                        + " WHERE seq > ? AND subject_id = ? AND action = ?",
                afterSeq, citizen.toString(), action);
    }

    private long headSeq() {
        return jdbc.queryForObject("SELECT COALESCE(max(seq), 0) FROM audit.audit_entry", Long.class);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private void awaitPointer() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (count("SELECT count(*) FROM registry_pointer WHERE subject_id = ? AND department_code = 'REVENUE'"
                    + " AND data_category = ?", citizen, DOC.code()) > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("registry pointer never arrived");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
