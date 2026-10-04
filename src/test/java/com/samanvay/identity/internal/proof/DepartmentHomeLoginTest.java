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
import com.nimbusds.jwt.SignedJWT;
import com.samanvay.catalog.api.Department;
import com.samanvay.catalog.api.DepartmentCatalog;
import com.samanvay.catalog.api.DepartmentDraft;
import com.samanvay.catalog.api.DepartmentIdentity;
import com.samanvay.identity.api.LinkProofInvalidException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A citizen signs in at their HOME department, so no Samanvay-issued state exists. The department's signed assertion is checked the
 * same strict way as for linking, and replay is stopped by remembering each assertion's {@code jti}.
 */
class DepartmentHomeLoginTest {

    static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final DepartmentIdentity ID = new DepartmentIdentity(
            "EDU_STUDENT_ID", "https://edu.example.gov/login", "https://edu.example.gov/.well-known/jwks.json", "dept:EDUCATION");

    ECKey key;
    DepartmentIdentity configured = ID;
    DepartmentHomeLogin home;
    final Set<String> seenJtis = new HashSet<>();

    @BeforeEach
    void setUp() throws JOSEException {
        key = new ECKeyGenerator(Curve.P_256).keyID("k1").generate();
        JWKSet published = new JWKSet(key.toPublicJWK());
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
                return "EDUCATION".equals(code) ? Optional.of(configured) : Optional.empty();
            }
        };
        AssertionUseStore uses = (dept, jti, expiresAt) -> seenJtis.add(dept + "|" + jti);
        home = new DepartmentHomeLogin(new DepartmentAssertionVerifier(departments, (url, force) -> published, CLOCK, Duration.ofMinutes(10)), uses);
    }

    JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer("dept:EDUCATION").audience("samanvay").subject("ST-1001")
                .claim("person_id_type", "EDU_STUDENT_ID").claim("dept_code", "EDUCATION")
                .claim("auth_time", NOW.minusSeconds(30).getEpochSecond())
                .issueTime(Date.from(NOW.minusSeconds(30))).expirationTime(Date.from(NOW.plusSeconds(270)))
                .jwtID(UUID.randomUUID().toString()).claim("state", "st").claim("nonce", "nn")
                .claim("name", "Meera Kulkarni").claim("dob", "2004-03-09");
    }

    String sign(JWTClaimsSet.Builder c) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("k1").build(), c.build());
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }

    void refused(String token) {
        assertThatThrownBy(() -> home.verify(token, "EDUCATION")).isInstanceOf(LinkProofInvalidException.class);
    }

    @Test
    void a_valid_assertion_gives_the_person_and_the_name_and_birth_date_the_department_vouched_for() throws Exception {
        VerifiedAssertion a = home.verify(sign(claims()), "EDUCATION");
        assertThat(a.departmentCode()).isEqualTo("EDUCATION");
        assertThat(a.personIdType()).isEqualTo("EDU_STUDENT_ID");
        assertThat(a.personId()).isEqualTo("ST-1001");
        assertThat(a.name()).isEqualTo("Meera Kulkarni");
        assertThat(a.dob()).isEqualTo(LocalDate.parse("2004-03-09"));
    }

    @Test
    void name_and_birth_date_are_optional() throws Exception {
        VerifiedAssertion a = home.verify(sign(claims().claim("name", null).claim("dob", null)), "EDUCATION");
        assertThat(a.name()).isNull();
        assertThat(a.dob()).isNull();
    }

    @Test
    void an_assertion_is_good_once_and_a_replay_is_refused() throws Exception {
        String token = sign(claims());
        home.verify(token, "EDUCATION");
        refused(token);
    }

    @Test
    void a_malformed_birth_date_is_ignored_not_trusted() throws Exception {
        assertThat(home.verify(sign(claims().claim("dob", "not-a-date")), "EDUCATION").dob()).isNull();
    }

    @Test
    void anything_else_that_is_wrong_is_refused_the_same_way() throws Exception {
        refused(sign(claims().audience("someone-else")));
        refused(sign(claims().claim("dept_code", "REVENUE")));
        refused(sign(claims().claim("person_id_type", "OTHER")));
        refused(sign(claims().expirationTime(Date.from(NOW.minusSeconds(600))).issueTime(Date.from(NOW.minusSeconds(900)))));
        refused(sign(claims().claim("auth_time", NOW.minus(Duration.ofHours(1)).getEpochSecond())));
        refused("not.a.jwt");
        SignedJWT hs = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims().build());
        hs.sign(new MACSigner(new byte[32]));
        refused(hs.serialize());
    }

    @Test
    void an_assertion_for_another_department_than_the_caller_claims_is_refused() throws Exception {
        assertThatThrownBy(() -> home.verify(sign(claims()), "REVENUE")).isInstanceOf(LinkProofInvalidException.class);
    }

    @Test
    void an_assertion_issued_in_the_future_beyond_the_clock_skew_is_refused() throws Exception {
        home.verify(sign(claims().issueTime(Date.from(NOW.plusSeconds(30))).expirationTime(Date.from(NOW.plusSeconds(300)))), "EDUCATION");
        refused(sign(claims().issueTime(Date.from(NOW.plusSeconds(120))).expirationTime(Date.from(NOW.plusSeconds(300)))));
    }

    @Test
    void not_before_is_honoured_within_the_clock_skew() throws Exception {
        home.verify(sign(claims().notBeforeTime(Date.from(NOW.plusSeconds(30)))), "EDUCATION");
        refused(sign(claims().notBeforeTime(Date.from(NOW.plusSeconds(120)))));
    }

    @Test
    void a_very_long_name_is_cut_to_200_characters_and_an_over_long_jti_or_subject_is_refused_not_a_server_error() throws Exception {
        assertThat(home.verify(sign(claims().claim("name", "N".repeat(5000))), "EDUCATION").name()).hasSize(200);
        assertThat(home.verify(sign(claims().jwtID("j".repeat(180))), "EDUCATION").jti()).hasSize(180);
        assertThat(home.verify(sign(claims().subject("s".repeat(180))), "EDUCATION").personId()).hasSize(180);
        refused(sign(claims().jwtID("j".repeat(181))));
        refused(sign(claims().subject("s".repeat(181))));
    }

    @Test
    void the_departments_keys_must_be_published_on_the_same_scheme_host_and_port_as_its_login() throws Exception {
        String token = sign(claims());
        configured = new DepartmentIdentity("EDU_STUDENT_ID", "https://edu.example.gov/login", "https://keys.example.net/jwks.json", "dept:EDUCATION");
        refused(token);
        configured = new DepartmentIdentity("EDU_STUDENT_ID", "https://edu.example.gov/login", "http://edu.example.gov/jwks.json", "dept:EDUCATION");
        refused(token);
        configured = new DepartmentIdentity("EDU_STUDENT_ID", "https://edu.example.gov/login", "https://edu.example.gov:8443/jwks.json", "dept:EDUCATION");
        refused(token);
        configured = new DepartmentIdentity("EDU_STUDENT_ID", "https://EDU.example.gov:443/login", "https://edu.example.gov/other/jwks.json", "dept:EDUCATION");
        assertThat(home.verify(token, "EDUCATION").personId()).isEqualTo("ST-1001");
    }
}
