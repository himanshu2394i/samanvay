package com.samanvay.identity.internal.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.samanvay.identity.api.AuthProof;
import com.samanvay.identity.api.LinkProofContext;
import com.samanvay.identity.api.LinkProofInvalidException;
import com.samanvay.identity.api.LinkProofKind;
import com.samanvay.identity.api.VerifiedLocalId;
import com.samanvay.identity.internal.repository.CitizenRepository;
import com.samanvay.shared.security.CitizenTokenVerifier;
import com.samanvay.shared.security.CitizenTokenVerifier.VerifiedToken;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DepartmentBrokerLinkProofProviderTest {

    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
    private static final String SUBJECT = "kc-sub-1";
    private static final UUID CITIZEN = UUID.randomUUID();

    private final CitizenRepository citizens = mock(CitizenRepository.class);
    /** Stands in for Keycloak: token string -> what a verified token would carry. */
    private final Map<String, VerifiedToken> verified = new HashMap<>();

    private final CitizenTokenVerifier verifier = raw -> Optional.ofNullable(verified.get(raw));
    private final DepartmentBrokerLinkProofProvider provider = new DepartmentBrokerLinkProofProvider(
            verifier, citizens, Clock.fixed(NOW, ZoneOffset.UTC), "dept-idp", Duration.ofMinutes(10));

    DepartmentBrokerLinkProofProviderTest() {
        when(citizens.existsByIdAndAuthSubject(CITIZEN, SUBJECT)).thenReturn(true);
    }

    @Test
    void brokeredLoginProvesTheDepartmentIdentityItCarries() {
        String token = token(claims());
        VerifiedLocalId id = provider.verify(AuthProof.departmentIdp(token), context("REVENUE", "RATION", "RC-1"));
        assertThat(id).isEqualTo(new VerifiedLocalId("RATION", "RC-1"));
        assertThat(provider.kind()).isEqualTo(LinkProofKind.DEPT_IDP);
        assertThat(provider.label()).isEqualTo("Department sign-in (mock department IdP)");
    }

    @Test
    void neverBindsToWhateverWasRequested() {
        String token = token(claims());
        assertRefused(token, context("REVENUE", "RATION", "RC-OTHER"));
        assertRefused(token, context("REVENUE", "PROPERTY", "RC-1"));
        assertRefused(token, context("EDUCATION", "RATION", "RC-1"));
        assertRefused(token, context("revenue", "RATION", "RC-1"));
    }

    @Test
    void wrongKindOrMissingProofIsRefused() {
        assertThatThrownBy(() -> provider.verify(null, context("REVENUE", "RATION", "RC-1")))
                .isInstanceOf(LinkProofInvalidException.class);
        assertThatThrownBy(() -> provider.verify(
                        new AuthProof(LinkProofKind.LOCAL_ID_OTP, token(claims())), context("REVENUE", "RATION", "RC-1")))
                .isInstanceOf(LinkProofInvalidException.class);
        assertRefused("  ", context("REVENUE", "RATION", "RC-1"));
        assertRefused(null, context("REVENUE", "RATION", "RC-1"));
    }

    @Test
    void incompleteLinkRequestIsRefused() {
        String token = token(claims());
        assertRefused(token, null);
        assertRefused(token, new LinkProofContext(null, "REVENUE", "RATION", "RC-1"));
        assertRefused(token, new LinkProofContext(CITIZEN, " ", "RATION", "RC-1"));
        assertRefused(token, new LinkProofContext(CITIZEN, "REVENUE", null, "RC-1"));
        assertRefused(token, new LinkProofContext(CITIZEN, "REVENUE", "RATION", ""));
    }

    @Test
    void aTokenThatFailsVerificationIsRefused() {
        // not registered in `verified`: forged, expired, wrong audience/azp/typ or malformed
        assertRefused("eyJ.forged.token", context("REVENUE", "RATION", "RC-1"));
    }

    @Test
    void tokenOfAnotherSubjectOrAnUnboundRecordIsRefused() {
        Map<String, Object> claims = claims();
        String otherPerson = "tok-other";
        verified.put(otherPerson, new VerifiedToken("someone-else", claims));
        assertRefused(otherPerson, context("REVENUE", "RATION", "RC-1"));

        UUID unbound = UUID.randomUUID(); // register() without an auth subject
        assertRefused(token(claims), new LinkProofContext(unbound, "REVENUE", "RATION", "RC-1"));
    }

    @Test
    void signInThatWasNotBrokeredThroughTheConfiguredIdpIsRefused() {
        // e.g. an email-code or passkey citizen token: no broker claims at all
        assertRefused(token(without(claims(), "dept_idp", "dept_code", "dept_local_id_type", "dept_local_id")),
                context("REVENUE", "RATION", "RC-1"));
        assertRefused(token(with(claims(), "dept_idp", "some-other-idp")), context("REVENUE", "RATION", "RC-1"));
        assertRefused(token(without(claims(), "dept_idp")), context("REVENUE", "RATION", "RC-1"));
    }

    @Test
    void missingOrBlankOrNonTextIdentityClaimsAreRefused() {
        for (String claim : new String[] {"dept_code", "dept_local_id_type", "dept_local_id"}) {
            assertRefused(token(without(claims(), claim)), context("REVENUE", "RATION", "RC-1"));
            assertRefused(token(with(claims(), claim, " ")), context("REVENUE", "RATION", "RC-1"));
            assertRefused(token(with(claims(), claim, 42)), context("REVENUE", "RATION", "RC-1"));
        }
    }

    @Test
    void theLoginItselfMustBeRecent() {
        Map<String, Object> justInside = with(claims(), "auth_time", NOW.minusSeconds(599).getEpochSecond());
        assertThat(provider.verify(AuthProof.departmentIdp(token(justInside)), context("REVENUE", "RATION", "RC-1")))
                .isNotNull();

        assertRefused(token(with(claims(), "auth_time", NOW.minusSeconds(601).getEpochSecond())),
                context("REVENUE", "RATION", "RC-1"));
        assertRefused(token(without(claims(), "auth_time")), context("REVENUE", "RATION", "RC-1"));
        assertRefused(token(with(claims(), "auth_time", "yesterday")), context("REVENUE", "RATION", "RC-1"));
        assertRefused(token(with(claims(), "auth_time", NOW.plusSeconds(3600).getEpochSecond())),
                context("REVENUE", "RATION", "RC-1"));
    }

    @Test
    void configurationIsValidated() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThatThrownBy(() -> new DepartmentBrokerLinkProofProvider(verifier, citizens, clock, " ", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DepartmentBrokerLinkProofProvider(verifier, citizens, clock, "dept-idp", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DepartmentBrokerLinkProofProvider(verifier, citizens, clock, "dept-idp", (Duration) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertRefused(String token, LinkProofContext context) {
        assertThatThrownBy(() -> provider.verify(AuthProof.departmentIdp(token), context))
                .isInstanceOf(LinkProofInvalidException.class);
    }

    /** Registers a verified token with the given claims and returns its (fake) compact form. */
    private String token(Map<String, Object> claims) {
        String raw = "tok-" + UUID.randomUUID();
        verified.put(raw, new VerifiedToken(SUBJECT, claims));
        return raw;
    }

    private static Map<String, Object> claims() {
        Map<String, Object> c = new HashMap<>();
        c.put("dept_idp", "dept-idp");
        c.put("dept_code", "REVENUE");
        c.put("dept_local_id_type", "RATION");
        c.put("dept_local_id", "RC-1");
        c.put("auth_time", NOW.minusSeconds(30).getEpochSecond());
        return c;
    }

    private static Map<String, Object> with(Map<String, Object> claims, String name, Object value) {
        claims.put(name, value);
        return claims;
    }

    private static Map<String, Object> without(Map<String, Object> claims, String... names) {
        for (String n : names) {
            claims.remove(n);
        }
        return claims;
    }

    private static LinkProofContext context(String department, String type, String id) {
        return new LinkProofContext(CITIZEN, department, type, id);
    }
}
