package com.samanvay.consent.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.SamanvayApplication;
import com.samanvay.consent.api.AuthProof;
import com.samanvay.consent.api.ConsentRequest;
import com.samanvay.consent.api.ConsentRequestDraft;
import com.samanvay.consent.api.ConsentService;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.PrincipalRef;
import com.samanvay.shared.PurposeCode;
import com.samanvay.shared.RequesterRef;
import com.samanvay.shared.SamanvayException;
import com.samanvay.shared.SubjectRef;
import com.samanvay.shared.test.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** A consent request is granted once: a double submit, or a grant after the department path already granted it, makes no second consent. */
@SpringBootTest(classes = SamanvayApplication.class)
class ConsentGrantIdempotencyIT extends PostgresIntegrationTest {

    @Autowired
    ConsentService consents;

    @Autowired
    ConsentServices services;

    @Autowired
    JdbcTemplate jdbc;

    private ConsentRequest open(UUID citizen) {
        return consents.request(new ConsentRequestDraft(citizen, "SCHOLARSHIP", "SCHOLARSHIP_ELIGIBILITY"));
    }

    /** The 4-argument form is the real one (the controller's), and it is the transactional one. */
    private com.samanvay.consent.api.ConsentArtifact grant(UUID request, UUID citizen, String jti) {
        return consents.grant(request, citizen, new AuthProof(jti), new PrincipalRef(PrincipalRef.Kind.CITIZEN, citizen.toString()));
    }

    private int activeConsents(UUID citizen) {
        return jdbc.queryForObject("SELECT count(*) FROM consent_artifact WHERE subject_citizen_id = ? AND status = 'ACTIVE'", Integer.class, citizen);
    }

    @Test
    void granting_the_same_request_twice_is_refused_with_a_409_and_makes_one_consent() {
        UUID citizen = UUID.randomUUID();
        ConsentRequest request = open(citizen);
        grant(request.id(), citizen, "jti-1");

        assertThatThrownBy(() -> grant(request.id(), citizen, "jti-2"))
                .isInstanceOfSatisfying(SamanvayException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.reason()).isEqualTo("CONSENT_REQUEST_NOT_PENDING");
                });
        assertThat(activeConsents(citizen)).isEqualTo(1);
    }

    @Test
    void two_simultaneous_grants_of_one_request_make_exactly_one_consent() throws Exception {
        UUID citizen = UUID.randomUUID();
        ConsentRequest request = open(citizen);
        int callers = 4;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                String jti = "jti-" + i;
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        grant(request.id(), citizen, jti);
                        return true;
                    } catch (SamanvayException refused) {
                        return false;
                    }
                }));
            }
            go.countDown();
            int granted = 0;
            for (Future<Boolean> r : results) {
                granted += r.get() ? 1 : 0;
            }
            assertThat(granted).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(activeConsents(citizen)).isEqualTo(1);
    }

    @Test
    void a_request_the_department_path_already_granted_cannot_be_granted_again_by_the_citizen() {
        UUID citizen = UUID.randomUUID();
        ConsentRequest request = open(citizen);
        jdbc.update("UPDATE consent_request SET status = 'GRANTED' WHERE id = ?", request.id());

        assertThatThrownBy(() -> grant(request.id(), citizen, "jti"))
                .isInstanceOf(SamanvayException.class);
        assertThat(activeConsents(citizen)).isZero();
    }

    @Test
    void a_grant_is_refused_when_the_catalog_changed_the_terms_since_the_request() {
        UUID citizen = UUID.randomUUID();
        ConsentRequest request = open(citizen);
        Integer before = jdbc.queryForObject("SELECT max_duration_days FROM catalog_purpose WHERE code = 'SCHOLARSHIP_ELIGIBILITY'", Integer.class);
        jdbc.update("UPDATE catalog_purpose SET max_duration_days = 30 WHERE code = 'SCHOLARSHIP_ELIGIBILITY'");
        try {
            assertThatThrownBy(() -> grant(request.id(), citizen, "jti"))
                    .isInstanceOfSatisfying(SamanvayException.class, e -> {
                        assertThat(e.status()).isEqualTo(409);
                        assertThat(e.reason()).isEqualTo("CONSENT_TERMS_CHANGED");
                    });
        } finally {
            jdbc.update("UPDATE catalog_purpose SET max_duration_days = ? WHERE code = 'SCHOLARSHIP_ELIGIBILITY'", before);
        }
        assertThat(activeConsents(citizen)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM consent_request WHERE id = ?", String.class, request.id())).isEqualTo("PENDING");
        assertThat(grant(request.id(), citizen, "jti-again").status()).as("the request is still grantable once the terms match").isEqualTo("ACTIVE");
    }

    @Test
    void the_active_consent_is_found_ahead_of_a_newer_ended_one() {
        UUID citizen = UUID.randomUUID();
        var first = grant(open(citizen).id(), citizen, "a");
        UUID ended = UUID.randomUUID();
        jdbc.update("INSERT INTO consent_artifact (id, subject_citizen_id, requester_id, purpose_code, purpose_text, data_categories,"
                + " granularity, valid_from, valid_until, status, version, citizen_auth_ref, created_at, updated_at)"
                + " SELECT ?, subject_citizen_id, requester_id, purpose_code, purpose_text, data_categories, granularity, valid_from,"
                + " valid_until, 'REVOKED', 2, 'x', created_at + interval '1 minute', updated_at FROM consent_artifact WHERE id = ?",
                ended, first.id());

        var match = services.find(new RequesterRef("SCHOLARSHIP"), new SubjectRef(citizen), DataCategory.INCOME_CERTIFICATE,
                PurposeCode.SCHOLARSHIP_ELIGIBILITY);
        assertThat(match).isPresent();
        assertThat(match.get().id()).isEqualTo(first.id());
    }
}
