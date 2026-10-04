package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.catalog.internal.domain.ConnectorEntity;
import com.samanvay.catalog.internal.domain.DataSourceEntity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class MockDepartmentsStartupCheckTest {

    private static DataSourceEntity source(String code, String host) {
        DataSourceEntity s = new DataSourceEntity();
        s.setCode(code);
        s.setBaseHost(host);
        return s;
    }

    private static ConnectorEntity connector(String sourceCode, String status) {
        ConnectorEntity c = new ConnectorEntity();
        c.setDataSourceCode(sourceCode);
        c.setStatus(status);
        return c;
    }

    @Test
    void namesMockSourcesThatAPublishedConnectorUses() {
        var problems = MockDepartmentsStartupCheck.problems(
                List.of(source("revenue-rest-mock", "mock.samanvay.test"), source("idle-mock", "mock.samanvay.test")),
                List.of(connector("revenue-rest-mock", "PUBLISHED"), connector("other", "PUBLISHED")));
        assertThat(problems).singleElement().asString().contains("revenue-rest-mock").doesNotContain("idle-mock");
    }

    @Test
    void deprecatedOrUnusedMockSourcesAreFine() {
        assertThat(MockDepartmentsStartupCheck.problems(
                        List.of(source("revenue-rest-mock", "mock.samanvay.test")),
                        List.of(connector("revenue-rest-mock", "DEPRECATED"))))
                .isEmpty();
        assertThat(MockDepartmentsStartupCheck.problems(List.of(), List.of(connector("x", "PUBLISHED")))).isEmpty();
    }

    @Test
    void theGuardAppliesOnlyOutsideDevDemoTestAndWithoutTheOverride() {
        assertThat(MockDepartmentsStartupCheck.enforced(new MockEnvironment())).isTrue();
        for (String p : new String[] {"dev", "demo", "test"}) {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles(p);
            assertThat(MockDepartmentsStartupCheck.enforced(env)).as(p).isFalse();
        }
        assertThat(MockDepartmentsStartupCheck.enforced(
                        new MockEnvironment().withProperty("samanvay.allow-mock-departments", "true")))
                .isFalse();
    }
}
