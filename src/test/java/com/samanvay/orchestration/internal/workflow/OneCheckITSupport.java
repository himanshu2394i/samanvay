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
 * Shared set-up for the one-check ITs: a linked citizen with an SCH_ELIGIBILITY_CHECK consent
 * (frequency ONCE_PER_DOCUMENT_PER_APPLICATION), the real AccessAuthority and ConsentUsage, and a
 * counting stub in place of the department call.
 */
abstract class OneCheckITSupport extends PostgresIntegrationTest {

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

        @Override
        public com.samanvay.connector.api.SourceOutcome<com.samanvay.connector.api.BankCheckAdapter.BankCheckAnswer> bankCheck(
                AccessGrant grant, String sourceCode,
                com.samanvay.connector.api.BankCheckAdapter.BankCheckRequest request) {
            throw new UnsupportedOperationException("not exercised in one-check tests");
        }
    }

    static ConnectorResult success() {
        return new ConnectorResult.Success(JsonMapper.builder().build().createObjectNode().put("ok", true), null);
    }

    AccessRequest fetch(String applicationId) {
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

    /** A check for one payment/instalment of an application (ONCE_PER_PAYMENT scope). */
    AccessRequest fetch(String applicationId, String paymentId) {
        AccessRequest base = fetch(applicationId);
        return new AccessRequest(
                base.subject(), base.requester(), base.category(), base.departmentCode(), base.connectorRef(),
                base.purpose(), base.journeyCode(), base.principal(), applicationId, paymentId);
    }

    static ExecutionInputs inputs() {
        return new ExecutionInputs(DOC, "wf-p2f", Map.of(), Map.of(), Map.of());
    }

    static String app() {
        return "app-p2f-" + UUID.randomUUID();
    }

    void insertUsage(String app, UUID grantId, String state, long ageSeconds) {
        jdbc.update("INSERT INTO consent_usage (id, consent_id, document_type, scope_key, grant_id, state,"
                        + " claimed_at, used_at, claim_token) VALUES (?, ?, ?, ?, ?, ?, now() - make_interval(secs => ?),"
                        + " CASE WHEN ? = 'USED' THEN now() END, gen_random_uuid())",
                UUID.randomUUID(), consentId, DOC.code(), app, grantId, state, (double) ageSeconds, state);
    }

    List<Map<String, Object>> usageRows(String app) {
        return jdbc.queryForList(
                "SELECT state, grant_id, claimed_at, claim_token FROM consent_usage WHERE consent_id = ? AND scope_key = ?",
                consentId, app);
    }

    /** Read on its own autocommit connection: sees only committed rows. */
    String committedState(UUID grantId) {
        List<String> s = jdbc.queryForList("SELECT state FROM consent_usage WHERE grant_id = ?", String.class, grantId);
        return s.isEmpty() ? null : s.get(0);
    }

    List<Map<String, Object>> audit(long afterSeq, String action) {
        return jdbc.queryForList(
                "SELECT outcome, reason, grant_id, meta::text AS meta FROM audit.audit_entry"
                        + " WHERE seq > ? AND subject_id = ? AND action = ?",
                afterSeq, citizen.toString(), action);
    }

    long headSeq() {
        return jdbc.queryForObject("SELECT COALESCE(max(seq), 0) FROM audit.audit_entry", Long.class);
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    void awaitPointer() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (count("SELECT count(*) FROM registry_pointer WHERE subject_id = ? AND department_code = 'REVENUE'"
                    + " AND data_category = ?", citizen, DOC.code()) > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("registry pointer never arrived");
    }

    static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    String url(String path) {
        return "http://localhost:" + port + path;
    }
}

