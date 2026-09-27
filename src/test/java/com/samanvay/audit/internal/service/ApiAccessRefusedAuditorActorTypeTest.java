package com.samanvay.audit.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.audit.api.ActorType;
import com.samanvay.shared.PrincipalRef;
import com.samanvay.shared.security.ApiAccessRefused;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ApiAccessRefusedAuditorActorTypeTest {

    @ParameterizedTest
    @EnumSource(PrincipalRef.Kind.class)
    void everyPrincipalKindKeepsItsOwnActorType(PrincipalRef.Kind kind) {
        assertThat(ApiAccessRefusedAuditor.actorType(kind.name())).isEqualTo(ActorType.valueOf(kind.name()));
    }

    @Test
    void reviewerIsNotRecordedAsOfficer() {
        assertThat(ApiAccessRefusedAuditor.actorType("REVIEWER")).isEqualTo(ActorType.REVIEWER);
    }

    @Test
    void roleLessTokenIsAuthenticatedNotAnonymous() {
        assertThat(ApiAccessRefusedAuditor.actorType(ApiAccessRefused.AUTHENTICATED)).isEqualTo(ActorType.AUTHENTICATED);
        assertThat(ApiAccessRefusedAuditor.actorType(ApiAccessRefused.ANONYMOUS)).isEqualTo(ActorType.ANONYMOUS);
    }
}
