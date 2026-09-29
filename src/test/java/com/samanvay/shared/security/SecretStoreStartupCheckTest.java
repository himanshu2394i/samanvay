package com.samanvay.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.shared.EnvSecretStore;
import com.samanvay.shared.FileSecretStore;
import com.samanvay.shared.RequiredSecrets;
import com.samanvay.shared.SecretStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;

class SecretStoreStartupCheckTest {

    private static final List<String> KEYS = List.of(
            "audit-checkpoint-signing-key",
            "audit-checkpoint-verifying-key",
            "consent-grant-signing-key",
            "consent-grant-verifying-key",
            "source-ifsc-bank-credential");

    @TestConfiguration
    @Import({SecretStoreStartupCheck.class, EnvSecretStore.class, FileSecretStore.class})
    static class Wired {}

    @TempDir
    Path dir;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(Wired.class)
                .withBean("requiredSecrets", RequiredSecrets.class, () -> () -> KEYS);
    }

    private void provision(String key) throws Exception {
        Files.writeString(dir.resolve(key), Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));
    }

    // ---- provider selection: exactly one SecretStore in each mode -------------------------

    @Test
    void defaultIsTheEnvStore_exactlyOneBean() {
        runner().withPropertyValues("spring.profiles.active=dev").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(SecretStore.class)).hasSize(1);
            assertThat(ctx).hasSingleBean(EnvSecretStore.class).doesNotHaveBean(FileSecretStore.class);
        });
    }

    @Test
    void explicitEnvProviderIsTheEnvStore() {
        runner().withPropertyValues("spring.profiles.active=demo", "samanvay.secrets.provider=env").run(ctx -> {
            assertThat(ctx.getBeansOfType(SecretStore.class)).hasSize(1);
            assertThat(ctx).hasSingleBean(EnvSecretStore.class);
        });
    }

    @Test
    void fileProviderIsTheOnlyStore() throws Exception {
        runner().withPropertyValues(
                        "spring.profiles.active=dev", "samanvay.secrets.provider=file", "samanvay.secrets.dir=" + dir)
                .run(ctx -> {
            assertThat(ctx.getBeansOfType(SecretStore.class)).hasSize(1);
            assertThat(ctx).hasSingleBean(FileSecretStore.class).doesNotHaveBean(EnvSecretStore.class);
        });
    }

    // ---- dev/demo unchanged ----------------------------------------------------------------

    @Test
    void devAndDemoBootWithTheEphemeralEnvStore() {
        for (String profile : new String[] {"dev", "demo"}) {
            runner().withPropertyValues("spring.profiles.active=" + profile).run(ctx -> assertThat(ctx)
                    .as(profile)
                    .hasNotFailed());
        }
    }

    @Test
    void devAndDemoBootEvenWithAnEmptyFileStore() {
        runner().withPropertyValues(
                        "spring.profiles.active=dev", "samanvay.secrets.provider=file", "samanvay.secrets.dir=" + dir)
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    // ---- production: no ephemeral keys, ever -----------------------------------------------

    @Test
    void prodRefusesTheEphemeralEnvStore_evenWithNoProfileAtAll() {
        for (String profile : new String[] {"spring.profiles.active=prod", "unrelated=1"}) {
            runner().withPropertyValues(profile).run(ctx -> {
                assertThat(ctx).as(profile).hasFailed();
                assertThat(ctx.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("Refusing to start")
                        .hasMessageContaining("generates ephemeral keys")
                        .hasMessageContaining("samanvay.secrets.provider=file");
            });
        }
    }

    @Test
    void prodWithAnEmptyFileStoreFailsNamingEveryMissingKey() {
        runner().withPropertyValues(
                        "spring.profiles.active=prod", "samanvay.secrets.provider=file", "samanvay.secrets.dir=" + dir)
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("Refusing to start")
                            .hasMessageContaining("audit-checkpoint-signing-key")
                            .hasMessageContaining("audit-checkpoint-verifying-key")
                            .hasMessageContaining("consent-grant-signing-key")
                            .hasMessageContaining("consent-grant-verifying-key")
                            .hasMessageContaining("source-ifsc-bank-credential");
                });
    }

    @Test
    void prodWithOneKeyStillMissingFails() throws Exception {
        for (String key : KEYS.subList(0, KEYS.size() - 1)) {
            provision(key);
        }
        runner().withPropertyValues(
                        "spring.profiles.active=prod", "samanvay.secrets.provider=file", "samanvay.secrets.dir=" + dir)
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("source-ifsc-bank-credential")
                            .hasMessageNotContaining("audit-checkpoint-signing-key");
                });
    }

    @Test
    void prodBootsWhenEveryRequiredKeyIsProvisioned() throws Exception {
        for (String key : KEYS) {
            provision(key);
        }
        runner().withPropertyValues(
                        "spring.profiles.active=prod", "samanvay.secrets.provider=file", "samanvay.secrets.dir=" + dir)
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void prodWithUnreadableKeyCountsAsMissing() throws Exception {
        for (String key : KEYS) {
            provision(key);
        }
        Files.writeString(dir.resolve("consent-grant-signing-key"), "***not base64***");
        runner().withPropertyValues(
                        "spring.profiles.active=prod", "samanvay.secrets.provider=file", "samanvay.secrets.dir=" + dir)
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("consent-grant-signing-key");
                });
    }

    // ---- the test-runtime escape hatch cannot disable the guard in a deployment --------------

    @Test
    void testOverrideWorksOnlyInATestRuntime() {
        SecretStore ephemeral = new EnvSecretStore();
        ObjectProvider<RequiredSecrets> none = new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                .getBeanProvider(RequiredSecrets.class);
        MockEnvironment prodWithOverride = new MockEnvironment().withProperty(SecretStoreStartupCheck.ALLOW_EPHEMERAL_FOR_TESTS, "true");

        // In a test runtime (JUnit present) the shared IT base may run on ephemeral keys...
        new SecretStoreStartupCheck(ephemeral, none, prodWithOverride, true).afterPropertiesSet();

        // ...but the same property in a deployment (no test runtime on the classpath) changes nothing.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new SecretStoreStartupCheck(ephemeral, none, prodWithOverride, false).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("generates ephemeral keys");
    }

    @Test
    void problemsAreNamesOnly_neverValues() throws Exception {
        provision("k");
        SecretStore store = new FileSecretStore(dir);
        List<String> problems = SecretStoreStartupCheck.problems(store, List.of("k", "missing-one"), false);

        assertThat(problems).singleElement().asString().contains("missing-one").doesNotContain("AQID");
        assertThat(SecretStoreStartupCheck.problems(new EnvSecretStore(), KEYS, true)).isEmpty();
    }
}
