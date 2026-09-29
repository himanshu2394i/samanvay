package com.samanvay.identity.internal.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.samanvay.identity.api.AuthProof;
import com.samanvay.identity.api.LinkProofContext;
import com.samanvay.identity.api.LinkProofInvalidException;
import com.samanvay.identity.api.LinkProofKind;
import com.samanvay.identity.api.LinkProofProvider;
import com.samanvay.identity.internal.repository.CitizenRepository;
import com.samanvay.shared.security.CitizenTokenVerifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Docker-free: the provider exists only when explicitly enabled, and reads its settings from configuration. */
class DepartmentBrokerProviderWiringTest {

    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
    private static final UUID CITIZEN = UUID.randomUUID();

    private ApplicationContextRunner runner() {
        CitizenRepository citizens = mock(CitizenRepository.class);
        when(citizens.existsByIdAndAuthSubject(CITIZEN, "sub")).thenReturn(true);
        CitizenTokenVerifier verifier = raw -> Optional.of(new CitizenTokenVerifier.VerifiedToken("sub", Map.of(
                "dept_idp", "dept-idp",
                "dept_code", "REVENUE",
                "dept_local_id_type", "RATION",
                "dept_local_id", "RC-1",
                "auth_time", NOW.minusSeconds(15 * 60).getEpochSecond())));
        return new ApplicationContextRunner()
                .withBean(CitizenTokenVerifier.class, () -> verifier)
                .withBean(CitizenRepository.class, () -> citizens)
                .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC))
                .withUserConfiguration(DepartmentBrokerLinkProofProvider.class);
    }

    @Test
    void absentByDefault() {
        runner().run(ctx -> assertThat(ctx.getBeansOfType(LinkProofProvider.class)).isEmpty());
        runner().withPropertyValues("samanvay.identity.department-idp.enabled=false")
                .run(ctx -> assertThat(ctx.getBeansOfType(LinkProofProvider.class)).isEmpty());
    }

    @Test
    void presentWhenEnabledWithTheDocumentedDefaults() {
        runner().withPropertyValues("samanvay.identity.department-idp.enabled=true").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            LinkProofProvider provider = ctx.getBean(LinkProofProvider.class);
            assertThat(provider.kind()).isEqualTo(LinkProofKind.DEPT_IDP);
            // default max age is 10 minutes: the fixture login is 15 minutes old
            assertThatThrownBy(() -> provider.verify(
                            AuthProof.departmentIdp("t"), new LinkProofContext(CITIZEN, "REVENUE", "RATION", "RC-1")))
                    .isInstanceOf(LinkProofInvalidException.class);
        });
    }

    @Test
    void maxAuthAgeAndAliasComeFromConfiguration() {
        runner().withPropertyValues(
                        "samanvay.identity.department-idp.enabled=true", "samanvay.identity.department-idp.max-auth-age=PT30M")
                .run(ctx -> assertThat(ctx.getBean(LinkProofProvider.class)
                                .verify(AuthProof.departmentIdp("t"), new LinkProofContext(CITIZEN, "REVENUE", "RATION", "RC-1"))
                                .localId())
                        .isEqualTo("RC-1"));
        runner().withPropertyValues(
                        "samanvay.identity.department-idp.enabled=true",
                        "samanvay.identity.department-idp.max-auth-age=PT30M",
                        "samanvay.identity.department-idp.alias=another-idp")
                .run(ctx -> assertThatThrownBy(() -> ctx.getBean(LinkProofProvider.class)
                                .verify(AuthProof.departmentIdp("t"), new LinkProofContext(CITIZEN, "REVENUE", "RATION", "RC-1")))
                        .isInstanceOf(LinkProofInvalidException.class));
    }

    @Test
    void aMalformedMaxAuthAgeFailsStartupInsteadOfBeingIgnored() {
        runner().withPropertyValues(
                        "samanvay.identity.department-idp.enabled=true", "samanvay.identity.department-idp.max-auth-age=soon")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
