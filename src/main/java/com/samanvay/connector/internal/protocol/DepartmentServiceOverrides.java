package com.samanvay.connector.internal.protocol;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * DEV/DEMO ONLY: where a data source's REAL REST/SOAP call goes when it has been pointed at the
 * standalone department service (see {@link DepartmentServiceProperties}).
 *
 * <p>Only the URL origin changes. The call still goes through the real {@code RestAdapter} /
 * {@code SoapAdapter} and {@link DeadlineHttp}, over the network. It never applies to a source whose
 * catalog host is the mock sentinel (the adapters route those to the in-process backend before
 * looking here), and with no configuration, {@link #baseUrl} is always empty, so default and
 * production behaviour is unchanged.
 */
@Component
class DepartmentServiceOverrides {

    /** No override for any source: what every non-dev/demo run and every plain unit test uses. */
    static final DepartmentServiceOverrides NONE = new DepartmentServiceOverrides(Map.of());

    private final Map<String, String> baseUrls;

    @Autowired
    DepartmentServiceOverrides(DepartmentServiceProperties properties, Environment environment) {
        this(validated(properties.urls(), environment));
    }

    private DepartmentServiceOverrides(Map<String, String> baseUrls) {
        this.baseUrls = baseUrls;
    }

    /** Test seam: overrides without a Spring environment (the profile guard is {@link #validated}'s job). */
    static DepartmentServiceOverrides of(Map<String, String> baseUrls) {
        return new DepartmentServiceOverrides(Map.copyOf(baseUrls));
    }

    /** The service's origin (no trailing slash) for this data source, if it has been pointed at one. */
    Optional<String> baseUrl(String dataSourceCode) {
        return Optional.ofNullable(baseUrls.get(dataSourceCode));
    }

    /** Test seam: honoured only when JUnit is on the classpath, so it cannot loosen a deployed jar. */
    static final String ALLOW_IN_TESTS = "samanvay.sources.department-service.allow-in-tests";

    private static Map<String, String> validated(Map<String, String> urls, Environment environment) {
        if (urls.isEmpty()) {
            return Map.of();
        }
        boolean testRuntime = ClassUtils.isPresent("org.junit.jupiter.api.Test", DepartmentServiceOverrides.class.getClassLoader());
        boolean allowInTests = testRuntime && environment.getProperty(ALLOW_IN_TESTS, Boolean.class, false);
        if (!environment.acceptsProfiles(Profiles.of("dev", "demo")) && !allowInTests) {
            throw new IllegalStateException(
                    "samanvay.sources.department-service.urls points real connector calls at a fake department "
                            + "service and is only allowed under the dev or demo profile");
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        urls.forEach((code, url) -> {
            URI uri = URI.create(url.trim());
            if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
                throw new IllegalStateException(
                        "samanvay.sources.department-service.urls." + code + " must be an http(s) URL with a host");
            }
            if (MockDepartmentBackend.HOST.equals(uri.getHost())) {
                throw new IllegalStateException(
                        "samanvay.sources.department-service.urls." + code + " must be a real host, not the mock sentinel");
            }
            out.put(code, url.trim().replaceAll("/+$", ""));
        });
        return Map.copyOf(out);
    }
}
