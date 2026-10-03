package com.samanvay.identity.internal.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.samanvay.catalog.api.Department;
import com.samanvay.catalog.api.DepartmentCatalog;
import com.samanvay.catalog.api.DepartmentDraft;
import com.samanvay.catalog.api.DepartmentIdentity;
import com.samanvay.identity.api.AuthProof;
import com.samanvay.identity.api.LinkProofContext;
import com.samanvay.identity.api.LinkProofInvalidException;
import com.samanvay.identity.api.LinkProofKind;
import com.samanvay.identity.api.VerifiedLocalId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A citizen proves who they are at a department by logging in there; the department's signed assertion is verified
 * against the keys it published, bound to the login this citizen started, and used once. Real ES256 signatures.
 */
class DepartmentAssertionLinkProofProviderTest {

    static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final DepartmentIdentity ID = new DepartmentIdentity(
            "REVENUE_PERSON_ID", "https://rev.example.gov/login", "https://rev.example.gov/.well-known/jwks.json", "dept:REVENUE");

    final UUID citizen = UUID.randomUUID();
    ECKey key;
    ECKey otherKey;
    JWKSet published;
    final AtomicInteger jwksFetches = new AtomicInteger();
    final AtomicInteger forcedRefetches = new AtomicInteger();
    final Set<String> issuedStates = new HashSet<>(); // citizen|dept|state|nonce
    DepartmentIdentity configured = ID;

    DepartmentAssertionLinkProofProvider provider;

    @BeforeEach
    void setUp() throws JOSEException {
        key = new ECKeyGenerator(Curve.P_256).keyID("k1").generate();
        otherKey = new ECKeyGenerator(Curve.P_256).keyID("k1").generate(); // same kid, different key material
        published = new JWKSet(key.toPublicJWK());
        DepartmentCatalog departments = new DepartmentCatalog() {
            @Override
            public Optional<Department> byCode(String code) {
                return Optional.empty();
            }

            @Override
            public List<Department> all() {
                return List.of();
            }

            @Override
            public Department register(DepartmentDraft draft) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<DepartmentIdentity> identity(String code) {
                return "REVENUE".equals(code) ? Optional.ofNullable(configured) : Optional.empty();
            }
        };
        JwksSource jwks = (url, forceRefresh) -> {
            jwksFetches.incrementAndGet();
            if (forceRefresh) {
                forcedRefetches.incrementAndGet();
            }
            return published;
        };
        DepartmentLoginStates states = new DepartmentLoginStates() {
            @Override
            public LoginState issue(UUID citizenId, String departmentCode) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean consume(UUID citizenId, String departmentCode, String state, String nonce) {
                return issuedStates.remove(citizenId + "|" + departmentCode + "|" + state + "|" + nonce);
            }
        };
        provider = new DepartmentAssertionLinkProofProvider(departments, jwks, states, CLOCK, Duration.ofMinutes(10));
        issuedStates.add(citizen + "|REVENUE|st-1|nn-1");
    }

    JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer("dept:REVENUE").audience("samanvay").subject("RV-1001")
                .claim("person_id_type", "REVENUE_PERSON_ID").claim("dept_code", "REVENUE")
                .claim("auth_time", NOW.minusSeconds(30).getEpochSecond())
                .issueTime(Date.from(NOW.minusSeconds(30))).expirationTime(Date.from(NOW.plusSeconds(270)))
                .jwtID(UUID.randomUUID().toString()).claim("state", "st-1").claim("nonce", "nn-1");
    }

    String sign(JWTClaimsSet.Builder c, ECKey signer, String kid) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(kid).build(), c.build());
        jwt.sign(new ECDSASigner(signer));
        return jwt.serialize();
    }

    AuthProof proof(String token) {
        return new AuthProof(LinkProofKind.DEPT_ASSERTION, token);
    }

    LinkProofContext ctx(String localId) {
        return new LinkProofContext(citizen, "REVENUE", "REVENUE_PERSON_ID", localId);
    }

    void refused(String token) {
        assertThatThrownBy(() -> provider.verify(proof(token), ctx(null))).isInstanceOf(LinkProofInvalidException.class);
    }

    @Test
    void a_valid_assertion_verifies_and_gives_the_person_id_and_its_type() throws Exception {
        VerifiedLocalId v = provider.verify(proof(sign(claims(), key, "k1")), ctx(null));
        assertThat(v).isEqualTo(new VerifiedLocalId("REVENUE_PERSON_ID", "RV-1001"));
    }

    @Test
    void the_kind_and_label_identify_the_department_login_proof() {
        assertThat(provider.kind()).isEqualTo(LinkProofKind.DEPT_ASSERTION);
        assertThat(provider.label()).isNotBlank();
    }

    @Test
    void a_localId_in_the_request_must_equal_the_asserted_person_id_when_given() throws Exception {
        String token = sign(claims(), key, "k1");
        assertThat(provider.verify(proof(token), ctx("RV-1001")).localId()).isEqualTo("RV-1001");
        issuedStates.add(citizen + "|REVENUE|st-1|nn-1");
        assertThatThrownBy(() -> provider.verify(proof(token), ctx("RV-9999"))).isInstanceOf(LinkProofInvalidException.class);
    }

    @Test
    void a_signature_from_another_key_is_refused_even_with_the_same_kid() throws Exception {
        refused(sign(claims(), otherKey, "k1"));
    }

    @Test
    void an_unknown_kid_triggers_one_forced_refetch_then_is_refused() throws Exception {
        refused(sign(claims(), key, "rotated-away"));
        assertThat(forcedRefetches.get()).isEqualTo(1);
    }

    @Test
    void a_rotated_key_is_accepted_after_a_refetch() throws Exception {
        ECKey rotated = new ECKeyGenerator(Curve.P_256).keyID("k2").generate();
        provider = new DepartmentAssertionLinkProofProvider(
                new DepartmentCatalog() {
                    public Optional<Department> byCode(String c) { return Optional.empty(); }
                    public List<Department> all() { return List.of(); }
                    public Department register(DepartmentDraft d) { throw new UnsupportedOperationException(); }
                    public Optional<DepartmentIdentity> identity(String c) { return Optional.of(ID); }
                },
                (url, force) -> force ? new JWKSet(List.of(key.toPublicJWK(), rotated.toPublicJWK())) : published,
                new DepartmentLoginStates() {
                    public LoginState issue(UUID c, String d) { throw new UnsupportedOperationException(); }
                    public boolean consume(UUID c, String d, String s, String n) { return true; }
                },
                CLOCK, Duration.ofMinutes(10));
        assertThat(provider.verify(proof(sign(claims(), rotated, "k2")), ctx(null)).localId()).isEqualTo("RV-1001");
    }

    @Test
    void an_unsigned_or_hmac_token_is_refused_no_algorithm_confusion() throws Exception {
        refused(new PlainJWT(claims().build()).serialize());
        SignedJWT hs = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("k1").build(), claims().build());
        hs.sign(new MACSigner(new byte[32]));
        refused(hs.serialize());
    }

    @Test
    void garbage_is_refused_with_the_generic_error() {
        refused("not-a-jwt");
        refused("a.b.c");
        assertThatThrownBy(() -> provider.verify(proof(""), ctx(null))).isInstanceOf(LinkProofInvalidException.class);
        assertThatThrownBy(() -> provider.verify(null, ctx(null))).isInstanceOf(LinkProofInvalidException.class);
        assertThatThrownBy(() -> provider.verify(new AuthProof(LinkProofKind.DEPT_IDP, "x"), ctx(null))).isInstanceOf(LinkProofInvalidException.class);
    }

    @Test
    void wrong_issuer_audience_department_or_person_id_type_are_each_refused() throws Exception {
        refused(sign(claims().issuer("dept:OTHER"), key, "k1"));
        refused(sign(claims().audience("someone-else"), key, "k1"));
        refused(sign(claims().claim("dept_code", "DBT"), key, "k1"));
        refused(sign(claims().claim("person_id_type", "DBT_ID"), key, "k1"));
    }

    @Test
    void a_missing_subject_or_jti_is_refused() throws Exception {
        refused(sign(claims().subject(null), key, "k1"));
        refused(sign(claims().jwtID(null), key, "k1"));
    }

    @Test
    void an_expired_assertion_is_refused_but_a_little_clock_skew_is_tolerated() throws Exception {
        refused(sign(claims().expirationTime(Date.from(NOW.minusSeconds(120))), key, "k1"));
        issuedStates.add(citizen + "|REVENUE|st-1|nn-1");
        assertThat(provider.verify(proof(sign(claims().expirationTime(Date.from(NOW.minusSeconds(20))), key, "k1")), ctx(null)).localId())
                .isEqualTo("RV-1001");
    }

    @Test
    void an_assertion_valid_for_longer_than_five_minutes_is_refused() throws Exception {
        refused(sign(claims().issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(301))), key, "k1"));
    }

    @Test
    void a_stale_or_future_login_time_is_refused() throws Exception {
        refused(sign(claims().claim("auth_time", NOW.minus(Duration.ofMinutes(11)).getEpochSecond()), key, "k1"));
        refused(sign(claims().claim("auth_time", NOW.plusSeconds(300).getEpochSecond()), key, "k1"));
        refused(sign(claims().claim("auth_time", null), key, "k1"));
    }

    @Test
    void the_login_state_is_single_use_so_a_replayed_assertion_is_refused() throws Exception {
        String token = sign(claims(), key, "k1");
        provider.verify(proof(token), ctx(null));
        refused(token);
    }

    @Test
    void a_state_started_by_another_citizen_or_for_another_department_or_a_wrong_nonce_is_refused() throws Exception {
        issuedStates.clear();
        issuedStates.add(UUID.randomUUID() + "|REVENUE|st-1|nn-1");
        issuedStates.add(citizen + "|DBT|st-1|nn-1");
        refused(sign(claims(), key, "k1"));
        issuedStates.add(citizen + "|REVENUE|st-1|other-nonce");
        refused(sign(claims(), key, "k1"));
    }

    @Test
    void a_bad_assertion_does_not_burn_the_citizens_pending_login() throws Exception {
        refused(sign(claims(), otherKey, "k1"));
        assertThat(provider.verify(proof(sign(claims(), key, "k1")), ctx(null)).localId()).isEqualTo("RV-1001");
    }

    @Test
    void a_department_with_no_published_login_cannot_be_linked_this_way() throws Exception {
        configured = null;
        refused(sign(claims(), key, "k1"));
        assertThatThrownBy(() -> provider.verify(proof(sign(claims(), key, "k1")),
                new LinkProofContext(citizen, "UNKNOWN", "X", null))).isInstanceOf(LinkProofInvalidException.class);
    }

    @Test
    void a_failure_fetching_the_departments_keys_is_the_generic_refusal_not_a_server_error() throws Exception {
        provider = new DepartmentAssertionLinkProofProvider(
                new DepartmentCatalog() {
                    public Optional<Department> byCode(String c) { return Optional.empty(); }
                    public List<Department> all() { return List.of(); }
                    public Department register(DepartmentDraft d) { throw new UnsupportedOperationException(); }
                    public Optional<DepartmentIdentity> identity(String c) { return Optional.of(ID); }
                },
                (url, force) -> { throw new IllegalStateException("department down"); },
                new DepartmentLoginStates() {
                    public LoginState issue(UUID c, String d) { throw new UnsupportedOperationException(); }
                    public boolean consume(UUID c, String d, String s, String n) { return true; }
                },
                CLOCK, Duration.ofMinutes(10));
        refused(sign(claims(), key, "k1"));
    }
}
