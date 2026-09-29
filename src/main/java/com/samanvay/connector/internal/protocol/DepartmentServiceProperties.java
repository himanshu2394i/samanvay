package com.samanvay.connector.internal.protocol;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DEV/DEMO ONLY: points named catalog data sources at a real, separately running department service
 * (the {@code simulators/} app) instead of their seeded host. Keyed by data-source code, value is the
 * service's base URL:
 *
 * <pre>
 * samanvay.sources.department-service.urls:
 *   sandbox-income-rest: http://localhost:8090
 *   sandbox-marks-soap: http://localhost:8090
 * </pre>
 *
 * <p>Empty by default (nothing is overridden), and only the {@code dev} and {@code demo} profiles may
 * set it: {@link DepartmentServiceOverrides} refuses to start otherwise. Only application-dev.yml and
 * application-demo.yml set it.
 */
@ConfigurationProperties("samanvay.sources.department-service")
public record DepartmentServiceProperties(Map<String, String> urls) {

    public DepartmentServiceProperties {
        urls = urls == null ? Map.of() : Map.copyOf(urls);
    }
}
