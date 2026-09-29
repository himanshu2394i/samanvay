package com.samanvay.connector.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.consent.api.AccessGrant;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ConnectorRuntimeRequiresGrantTest {

    /** Every way into a department source goes through a grant: no ConnectorRuntime method without one. */
    @Test
    void everyRuntimeMethodRequiresAccessGrant() {
        Method[] methods = ConnectorRuntime.class.getDeclaredMethods();
        assertThat(methods).extracting(Method::getName).containsExactlyInAnyOrder("execute", "bankCheck");
        for (Method method : methods) {
            assertThat(Arrays.asList(method.getParameterTypes())).as(method.getName()).first().isEqualTo(AccessGrant.class);
        }
    }
}
