package com.samanvay.catalog.internal.service;

import com.samanvay.catalog.internal.domain.ConnectorEntity;
import com.samanvay.catalog.internal.domain.DataSourceEntity;
import com.samanvay.catalog.internal.repository.ConnectorRepository;
import com.samanvay.catalog.internal.repository.DataSourceRepository;
import com.samanvay.shared.security.DevProfiles;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fail-fast startup check: migrations V21-V23 seed department stand-ins on the sentinel host
 * {@code mock.samanvay.test} as PUBLISHED connectors that answer with fabricated data. Outside the dev/demo/test
 * profiles a deployment must not serve those, so startup refuses while any PUBLISHED connector still uses a data
 * source whose host ends with {@value #MOCK_SUFFIX}. Retire them (set the connectors DEPRECATED) or, knowingly,
 * set {@value #ALLOW} to true. Flyway checksums forbid editing the seed migrations, hence a guard instead.
 */
@Component
class MockDepartmentsStartupCheck implements SmartInitializingSingleton {

    static final String MOCK_SUFFIX = ".samanvay.test";
    static final String ALLOW = "samanvay.allow-mock-departments";

    private static final Logger log = LoggerFactory.getLogger(MockDepartmentsStartupCheck.class);

    private final DataSourceRepository sources;
    private final ConnectorRepository connectors;
    private final Environment env;

    MockDepartmentsStartupCheck(DataSourceRepository sources, ConnectorRepository connectors, Environment env) {
        this.sources = sources;
        this.connectors = connectors;
        this.env = env;
    }

    /** Runs after every singleton exists, so after Flyway has applied the seeds. */
    @Override
    public void afterSingletonsInstantiated() {
        if (DevProfiles.activeOrTest(env)) {
            return;
        }
        List<String> problems = problems(sources.findByBaseHostEndingWith(MOCK_SUFFIX), connectors.findByStatus("PUBLISHED"));
        if (problems.isEmpty()) {
            return;
        }
        if (!enforced(env)) {
            log.error("{}=true: serving fabricated department data. {}", ALLOW, problems);
            return;
        }
        throw new IllegalStateException("Refusing to start: " + String.join("; ", problems));
    }

    static boolean enforced(Environment env) {
        return !DevProfiles.activeOrTest(env) && !env.getProperty(ALLOW, Boolean.class, false);
    }

    static List<String> problems(List<DataSourceEntity> mockSources, List<ConnectorEntity> published) {
        Set<String> used = published.stream().filter(c -> "PUBLISHED".equals(c.getStatus())).map(ConnectorEntity::getDataSourceCode).collect(Collectors.toSet());
        List<String> codes = mockSources.stream().map(DataSourceEntity::getCode).filter(used::contains).sorted().toList();
        if (codes.isEmpty()) {
            return List.of();
        }
        return List.of("PUBLISHED connectors still use mock department data sources (host *" + MOCK_SUFFIX + "): "
                + String.join(", ", codes) + "; set those connectors to DEPRECATED, or set " + ALLOW
                + "=true to accept fabricated data knowingly");
    }
}
