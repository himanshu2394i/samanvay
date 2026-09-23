package com.samanvay.orchestration.internal.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.consent.api.AccessAuthority;
import com.samanvay.connector.api.ConnectorRuntime;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class NoBypassOfAccessAuthorityTest {

    @Test
    void fetchDelegateHoldsBothAuthorityAndRuntime() {
        boolean authority = false;
        boolean runtime = false;
        for (Field f : FetchDataDelegate.class.getDeclaredFields()) {
            if (AccessAuthority.class.isAssignableFrom(f.getType())) {
                authority = true;
            }
            if (ConnectorRuntime.class.isAssignableFrom(f.getType())) {
                runtime = true;
            }
        }
        assertThat(authority).isTrue();
        assertThat(runtime).isTrue();
        assertThat(Arrays.stream(FetchDataDelegate.class.getDeclaredFields())
                        .anyMatch(f -> f.getType().getName().contains("connector.internal")))
                .isFalse();
    }
}
