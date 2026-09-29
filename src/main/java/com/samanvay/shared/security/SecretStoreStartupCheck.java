package com.samanvay.shared.security;

import com.samanvay.shared.RequiredSecrets;
import com.samanvay.shared.SecretStore;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * Fail-fast startup check, the secrets twin of {@link RealmIssuerStartupCheck}: outside
 * the dev/demo profiles there are no ephemeral keys. The active {@link SecretStore} must
 * be a provisioned one (it must not {@linkplain SecretStore#mayGenerate() generate}) and
 * every key that any module declares through {@link RequiredSecrets} (audit checkpoint
 * signing/verifying key, consent grant keys, the credential of every LIVE source) must be
 * present via {@link SecretStore#find}. A misconfigured production boot stops here, naming
 * the missing keys (never values), instead of signing audit checkpoints with a key that
 * dies on restart.
 *
 * <p>Dev/demo are untouched. The one other exception is the test runtime: the shared
 * integration-test base sets {@value #ALLOW_EPHEMERAL_FOR_TESTS}, which is honoured only
 * when JUnit is on the classpath, so it cannot switch the guard off in a deployed jar
 * (test-scope dependencies are not packaged). Even then it logs a warning.
 */
@Component
class SecretStoreStartupCheck implements InitializingBean {

    static final String ALLOW_EPHEMERAL_FOR_TESTS = "samanvay.secrets.allow-ephemeral-keys";

    private static final Logger log = LoggerFactory.getLogger(SecretStoreStartupCheck.class);

    private final SecretStore store;
    private final ObjectProvider<RequiredSecrets> required;
    private final Environment env;

    private final boolean testRuntime;

    @Autowired
    SecretStoreStartupCheck(SecretStore store, ObjectProvider<RequiredSecrets> required, Environment env) {
        this(store, required, env, ClassUtils.isPresent("org.junit.jupiter.api.Test", SecretStoreStartupCheck.class.getClassLoader()));
    }

    SecretStoreStartupCheck(
            SecretStore store, ObjectProvider<RequiredSecrets> required, Environment env, boolean testRuntime) {
        this.store = store;
        this.required = required;
        this.env = env;
        this.testRuntime = testRuntime;
    }

    @Override
    public void afterPropertiesSet() {
        boolean devOrDemo = DevProfiles.active(env);
        boolean testOverride = env.getProperty(ALLOW_EPHEMERAL_FOR_TESTS, Boolean.class, false);
        if (!devOrDemo && testOverride && testRuntime) {
            log.warn("{}=true: ephemeral secrets allowed because this is a test runtime; never set this in a deployment",
                    ALLOW_EPHEMERAL_FOR_TESTS);
            return;
        }
        List<String> keys = new ArrayList<>();
        required.orderedStream().forEach(r -> keys.addAll(r.keys()));
        List<String> problems = problems(store, keys, devOrDemo);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start: " + String.join("; ", problems));
        }
    }

    static List<String> problems(SecretStore store, List<String> requiredKeys, boolean devOrDemo) {
        List<String> out = new ArrayList<>();
        if (devOrDemo) {
            return out;
        }
        if (store.mayGenerate()) {
            out.add("the SecretStore (" + store.getClass().getSimpleName() + ") generates ephemeral keys, which is only"
                    + " allowed in the dev/demo profiles; set samanvay.secrets.provider=file and"
                    + " samanvay.secrets.dir to a directory of mounted secrets");
            return out;
        }
        Set<String> missing = new LinkedHashSet<>();
        for (String key : requiredKeys) {
            try {
                if (store.find(key).filter(s -> s.bytes() != null && s.bytes().length > 0).isEmpty()) {
                    missing.add(key);
                }
            } catch (RuntimeException e) {
                // Unreadable / malformed counts as not provisioned; the message is dropped (it could echo content).
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            out.add("required secrets are not provisioned in the SecretStore: " + String.join(", ", missing));
        }
        return out;
    }
}
