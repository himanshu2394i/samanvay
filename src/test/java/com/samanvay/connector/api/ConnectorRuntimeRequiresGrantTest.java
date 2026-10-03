package com.samanvay.connector.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.consent.api.AccessGrant;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ConnectorRuntimeRequiresGrantTest {

    /**
     * Every way into a department source goes through a grant: no ConnectorRuntime method without one. The single
     * exception is {@code trial}: an admin's no-citizen test of a draft connector on the department's own sample person,
     * audited as CONNECTOR_TRIAL, no grant and no consent because no citizen data is involved.
     */
    @Test
    void everyRuntimeMethodRequiresAccessGrant() {
        Method[] methods = ConnectorRuntime.class.getDeclaredMethods();
        assertThat(methods).extracting(Method::getName).containsExactlyInAnyOrder("execute", "bankCheck", "trial");
        for (Method method : methods) {
            if (method.getName().equals("trial")) {
                assertThat(Arrays.asList(method.getParameterTypes())).as("trial is admin-attributed").contains(com.samanvay.shared.PrincipalRef.class);
                continue;
            }
            assertThat(Arrays.asList(method.getParameterTypes())).as(method.getName()).first().isEqualTo(AccessGrant.class);
        }
    }
}
