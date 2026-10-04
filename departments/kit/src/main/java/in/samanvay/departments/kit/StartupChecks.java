package in.samanvay.departments.kit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

/**
 * Refuses to start a department that would run with a published dev default or with weak or plain-http settings. It fails closed:
 * the only way past is {@code department.demo-mode=true} (default {@code false}), which a throwaway demo sets on purpose and which
 * is logged loudly. A local run uses the {@code dev} profile ({@code application-dev.yml}), which sets it.
 *
 * <p>Checked unless demo mode: the public address is https; the one-time code is set and is not the default 123456; a configured
 * session secret is at least 32 bytes; the Samanvay client secret is set; and NO secret-like property (a name containing key, secret,
 * password or token) anywhere in the configuration still holds a value ending {@code change-me}. That last rule covers every
 * department's own API key, client secret and WS-Security password without each one having to be listed. Only property NAMES are put
 * in the message, never values.
 */
public final class StartupChecks {

    static final String DEMO_PROPERTY = "department.demo-mode";
    static final int MIN_SESSION_SECRET_BYTES = 32;
    private static final Pattern SECRET_NAME = Pattern.compile("(?i).*(key|secret|password|token).*");
    private static final Logger log = LoggerFactory.getLogger(StartupChecks.class);

    public StartupChecks(PortalProperties p, Environment env) {
        if (Boolean.TRUE.equals(env.getProperty(DEMO_PROPERTY, Boolean.class, false))) {
            log.warn("DEMO MODE ({}=true): dev defaults, the fixed one-time code and plain http are allowed. Never use this for real citizens.", DEMO_PROPERTY);
            return;
        }
        List<String> problems = new ArrayList<>();
        if (p.publicBaseUrl() == null || !p.publicBaseUrl().toLowerCase().startsWith("https://")) {
            problems.add("portal.public-base-url must be an https address");
        }
        if (PortalProperties.DEFAULT_OTP.equals(p.otpCode())) {
            problems.add("portal.otp-code is not set or is the default code 123456; set a code of your own");
        }
        String secret = p.sessionSecret();
        if (secret != null && !secret.isBlank()) {
            if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SESSION_SECRET_BYTES) {
                problems.add("portal.session-secret is shorter than " + MIN_SESSION_SECRET_BYTES + " bytes");
            }
            if (secret.endsWith("change-me")) {
                problems.add("portal.session-secret is still a change-me default");
            }
        }
        String client = p.samanvay().clientSecret();
        if (client == null || client.isBlank()) {
            problems.add("portal.samanvay.client-secret is not set");
        } else if (client.endsWith("change-me")) {
            problems.add("portal.samanvay.client-secret is still a change-me default");
        }
        if (env instanceof ConfigurableEnvironment configurable) {
            for (String name : weakDefaults(configurable)) {
                problems.add(name + " is still a change-me default");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start: " + String.join("; ", problems.stream().distinct().toList())
                    + ". Fix the configuration, or set " + DEMO_PROPERTY + "=true for a throwaway demo.");
        }
    }

    private static List<String> weakDefaults(ConfigurableEnvironment env) {
        List<String> found = new ArrayList<>();
        for (PropertySource<?> source : env.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (!SECRET_NAME.matcher(name).matches()) {
                    continue;
                }
                try {
                    String value = env.getProperty(name);
                    if (value != null && value.endsWith("change-me") && !found.contains(name)) {
                        found.add(name);
                    }
                } catch (RuntimeException unresolvable) {
                    // a placeholder that cannot be resolved is not a default value
                }
            }
        }
        return found;
    }
}
