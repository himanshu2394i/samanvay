package com.samanvay.connector.internal.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/** The dev/demo department-service override: off by default, refused outside dev/demo, bound by the profile ymls. */
class DepartmentServiceOverridesTest {

    static final Map<String, String> URLS = Map.of("revenue-rest", "http://localhost:8090/");

    static DepartmentServiceOverrides overrides(Map<String, String> urls, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new DepartmentServiceOverrides(new DepartmentServiceProperties(urls), env);
    }

    @Test
    void nothing_configured_overrides_nothing_in_any_profile() {
        assertThat(overrides(Map.of()).baseUrl("revenue-rest")).isEmpty();
        assertThat(overrides(Map.of(), "prod").baseUrl("revenue-rest")).isEmpty();
        assertThat(DepartmentServiceOverrides.NONE.baseUrl("revenue-rest")).isEmpty();
    }

    @Test
    void dev_and_demo_may_override_and_a_trailing_slash_is_dropped() {
        for (String profile : new String[] {"dev", "demo"}) {
            DepartmentServiceOverrides o = overrides(URLS, profile);
            assertThat(o.baseUrl("revenue-rest")).contains("http://localhost:8090");
            assertThat(o.baseUrl("some-other-source")).isEmpty();
        }
    }

    @Test
    void any_other_profile_refuses_to_start_with_an_override() {
        assertThatThrownBy(() -> overrides(URLS)).isInstanceOf(IllegalStateException.class).hasMessageContaining("dev or demo");
        assertThatThrownBy(() -> overrides(URLS, "prod")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_bad_or_mock_url_is_refused() {
        assertThatThrownBy(() -> overrides(Map.of("s", "localhost:8090"), "dev")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> overrides(Map.of("s", "ftp://localhost"), "dev")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> overrides(Map.of("s", "http://" + MockDepartmentBackend.HOST), "dev"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mock");
    }

    // ---- the shipped profile ymls, bound for real ------------------------------------------------

    static ApplicationContextRunner runner(String... properties) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(DepartmentServiceConfig.class, DepartmentServiceOverrides.class)
                .withPropertyValues(properties);
    }

    @Test
    void default_profile_has_no_override() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(DepartmentServiceOverrides.class).baseUrl("revenue-rest")).isEmpty();
            assertThat(ctx.getBean(DepartmentServiceOverrides.class).baseUrl("education-soap")).isEmpty();
        });
    }

    @Test
    void demo_and_dev_profiles_point_the_sandbox_sources_at_the_department_service() {
        for (String profile : new String[] {"demo", "dev"}) {
            runner("spring.profiles.active=" + profile).run(ctx -> {
                assertThat(ctx).as(profile).hasNotFailed();
                DepartmentServiceOverrides o = ctx.getBean(DepartmentServiceOverrides.class);
                assertThat(o.baseUrl("revenue-rest")).as(profile).contains("http://localhost:8091");
                assertThat(o.baseUrl("education-soap")).as(profile).contains("http://localhost:8093");
                assertThat(o.baseUrl("revenue-sftp")).as(profile).isEmpty();
            });
        }
    }

    @Test
    void the_url_can_be_changed_for_a_compose_network() {
        runner("spring.profiles.active=demo", "SAMANVAY_EDUCATION_URL=http://department-education:8093").run(ctx ->
                assertThat(ctx.getBean(DepartmentServiceOverrides.class).baseUrl("education-soap"))
                        .contains("http://department-education:8093"));
    }

    @Test
    void the_override_property_is_refused_outside_dev_and_demo() {
        runner("spring.profiles.active=prod", "samanvay.sources.department-service.urls.revenue-rest=http://localhost:8090")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
