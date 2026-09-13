package com.samanvay.connector.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.consent.api.AccessGrant;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ConnectorRuntimeRequiresGrantTest {

    @Test
    void executeAlwaysRequiresAccessGrant() {
        Method[] methods = ConnectorRuntime.class.getDeclaredMethods();
        assertThat(methods).hasSize(1);
        assertThat(methods[0].getName()).isEqualTo("execute");
        assertThat(Arrays.asList(methods[0].getParameterTypes())).contains(AccessGrant.class);
    }
}
