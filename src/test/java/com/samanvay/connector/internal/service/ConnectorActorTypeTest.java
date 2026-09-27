package com.samanvay.connector.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.audit.api.ActorType;
import com.samanvay.consent.api.AccessGrant;
import com.samanvay.shared.PrincipalRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** DATA_ACCESSED / GRANT_REJECTED keep the grant principal's own kind (a reviewer is not an officer). */
class ConnectorActorTypeTest {

    @ParameterizedTest
    @EnumSource(PrincipalRef.Kind.class)
    void actorTypeMirrorsPrincipalKind(PrincipalRef.Kind kind) {
        AccessGrant grant = new AccessGrant(null, null, null, 1, null, null, null, null, null, null,
                new PrincipalRef(kind, "p-1"), null, null, null);
        assertThat(ConnectorRuntimeImpl.actorType(grant)).isEqualTo(ActorType.valueOf(kind.name()));
    }
}
