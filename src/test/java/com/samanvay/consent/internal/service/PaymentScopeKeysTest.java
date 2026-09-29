package com.samanvay.consent.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.shared.EnvSecretStore;
import com.samanvay.shared.SecretStore;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** ONCE_PER_PAYMENT scope keys: a keyed HMAC-SHA256 of the payment id, versioned by key id. */
class PaymentScopeKeysTest {

    private static final String PAYMENT = "3f1c2d4e-0000-4000-8000-00000000abcd";

    /** Provisioned secrets only, like FileSecretStore: absent means absent. */
    static final class MapSecretStore implements SecretStore {
        final Map<String, byte[]> secrets = new HashMap<>();

        MapSecretStore with(String name, String value) {
            secrets.put(name, value.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        @Override
        public Secret resolve(String key) {
            return find(key).orElseThrow(() -> new IllegalStateException("not provisioned: " + key));
        }

        @Override
        public Optional<Secret> find(String key) {
            return Optional.ofNullable(secrets.get(key)).map(Secret::new);
        }
    }

    private static String hmacHex(String key, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void scopeKeyIsTheKeyedHmacOfThePaymentIdNotTheRawId() throws Exception {
        var keys = new PaymentScopeKeys(new MapSecretStore().with("consent-payment-scope-key", "k1-secret"), "v1", List.of());
        PaymentScopeKeys.Scope scope = keys.active(PAYMENT);
        assertThat(scope.scopeKey()).isEqualTo("PAYMENT:" + hmacHex("k1-secret", PAYMENT));
        assertThat(scope.scopeKey()).doesNotContain(PAYMENT).doesNotContain("abcd");
        assertThat(scope.keyVersion()).isEqualTo("v1");
        assertThat(keys.active(PAYMENT)).as("deterministic").isEqualTo(scope);
        assertThat(keys.active(PAYMENT + "1").scopeKey()).as("per payment id").isNotEqualTo(scope.scopeKey());
        assertThat(scope.scopeKey().length()).as("fits consent_usage.scope_key VARCHAR(100)").isLessThanOrEqualTo(100);
    }

    @Test
    void theHashDependsOnTheKeySoItCannotBeRecomputedFromTheIdAlone() {
        var a = new PaymentScopeKeys(new MapSecretStore().with("consent-payment-scope-key", "key-a"), "v1", List.of());
        var b = new PaymentScopeKeys(new MapSecretStore().with("consent-payment-scope-key", "key-b"), "v1", List.of());
        assertThat(a.active(PAYMENT).scopeKey()).isNotEqualTo(b.active(PAYMENT).scopeKey());
    }

    @Test
    void aRotatedKeyHasItsOwnVersionAndSecretName() {
        var store = new MapSecretStore()
                .with("consent-payment-scope-key", "old")
                .with("consent-payment-scope-key-v2", "new");
        var keys = new PaymentScopeKeys(store, "v2", List.of("v1"));
        assertThat(keys.active(PAYMENT).keyVersion()).isEqualTo("v2");
        assertThat(keys.retired(PAYMENT)).singleElement().satisfies(s -> {
            assertThat(s.keyVersion()).isEqualTo("v1");
            assertThat(s.scopeKey()).isEqualTo(new PaymentScopeKeys(store, "v1", List.of()).active(PAYMENT).scopeKey());
        });
        assertThat(keys.active(PAYMENT).scopeKey()).isNotEqualTo(keys.retired(PAYMENT).get(0).scopeKey());
        assertThat(PaymentScopeKeys.secretName("v1")).isEqualTo("consent-payment-scope-key");
        assertThat(PaymentScopeKeys.secretName("v2")).isEqualTo("consent-payment-scope-key-v2");
    }

    @Test
    void theBootGuardIsToldToRequireTheActiveAndRetiredKeys() {
        var keys = new PaymentScopeKeys(new MapSecretStore(), "v3", List.of("v1", " v2 ", ""));
        assertThat(keys.keys()).containsExactly(
                "consent-payment-scope-key-v3", "consent-payment-scope-key", "consent-payment-scope-key-v2");
    }

    @Test
    void anUnprovisionedKeyFailsClosedWhenTheStoreCannotGenerate() {
        var keys = new PaymentScopeKeys(new MapSecretStore(), "v1", List.of());
        assertThatThrownBy(() -> keys.active(PAYMENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("consent-payment-scope-key")
                .hasMessageContaining("not provisioned");
    }

    @Test
    void anEmptyProvisionedKeyIsNotAKey() {
        var store = new MapSecretStore();
        store.secrets.put("consent-payment-scope-key", new byte[0]);
        assertThatThrownBy(() -> new PaymentScopeKeys(store, "v1", List.of()).active(PAYMENT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theDevStubFallsBackToAnInProcessKeyThatStaysStableWithinTheProcess() {
        var keys = new PaymentScopeKeys(new EnvSecretStore(), "v1", List.of());
        PaymentScopeKeys.Scope first = keys.active(PAYMENT);
        assertThat(keys.active(PAYMENT)).isEqualTo(first);
        assertThat(first.scopeKey()).startsWith("PAYMENT:").doesNotContain(PAYMENT);
    }

    @Test
    void aBlankPaymentIdIsRejected() {
        var keys = new PaymentScopeKeys(new MapSecretStore().with("consent-payment-scope-key", "k"), "v1", List.of());
        assertThatThrownBy(() -> keys.active(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> keys.active(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keyIdsAreValidated() {
        var store = new MapSecretStore();
        assertThatThrownBy(() -> new PaymentScopeKeys(store, "../etc", List.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PaymentScopeKeys(store, "v1", List.of("bad id"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PaymentScopeKeys(store, "v2", List.of("v2")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("also listed as retired");
    }
}
