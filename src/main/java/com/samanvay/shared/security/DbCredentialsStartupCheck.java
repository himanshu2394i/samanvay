package com.samanvay.shared.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * Fail-fast startup check: outside the dev/demo/test profiles the two database passwords must be
 * provisioned ({@code SAMANVAY_APP_DB_PASSWORD}, {@code SAMANVAY_MIGRATE_DB_PASSWORD}) and must not be the
 * repo defaults. The base {@code application.yml} carries no password; the local defaults live in
 * application-dev/-demo.yml.
 *
 * <p>This is an {@link ApplicationListener} (registered in META-INF/spring.factories) rather than a bean on purpose:
 * Flyway runs during context refresh and V1 creates the app role with the app password, so the check must stop
 * the boot before any bean exists. It fires on {@link ApplicationPreparedEvent}, after test-time dynamic properties
 * are applied and before refresh. Messages name the variables, never the values.
 */
public class DbCredentialsStartupCheck implements ApplicationListener<ApplicationPreparedEvent> {

    static final String REPO_DEFAULT_APP_PASSWORD = "samanvay_app_dev_password";
    static final String REPO_DEFAULT_MIGRATE_PASSWORD = "samanvay_migrate";

    @Override
    public void onApplicationEvent(ApplicationPreparedEvent event) {
        List<String> problems = problems(event.getApplicationContext().getEnvironment());
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start: " + String.join("; ", problems));
        }
    }

    static List<String> problems(Environment env) {
        List<String> out = new ArrayList<>();
        if (DevProfiles.activeOrTest(env)) {
            return out;
        }
        check(out, env.getProperty("spring.datasource.password"), "SAMANVAY_APP_DB_PASSWORD", REPO_DEFAULT_APP_PASSWORD);
        check(out, env.getProperty("spring.flyway.password"), "SAMANVAY_MIGRATE_DB_PASSWORD", REPO_DEFAULT_MIGRATE_PASSWORD);
        return out;
    }

    private static void check(List<String> out, String value, String variable, String repoDefault) {
        if (value == null || value.isBlank()) {
            out.add(variable + " is not set");
        } else if (value.equals(repoDefault)) {
            out.add(variable + " is still the repo default; set a real secret outside the dev/demo/test profiles");
        }
    }
}
