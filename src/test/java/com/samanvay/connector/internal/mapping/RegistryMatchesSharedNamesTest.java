package com.samanvay.connector.internal.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RegistryMatchesSharedNamesTest {

    @Test
    void registryMatchesSharedConstant() {
        assertThat(MappingExecutor.matchesSharedNames()).isTrue();
    }
}
