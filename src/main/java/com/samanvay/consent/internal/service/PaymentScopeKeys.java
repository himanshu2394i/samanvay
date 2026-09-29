package com.samanvay.consent.internal.service;

import com.samanvay.shared.RequiredSecrets;
import com.samanvay.shared.SecretStore;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Scope keys for {@code ONCE_PER_PAYMENT} consents: a keyed HMAC-SHA256 of the payment/instalment
 * id, so {@code consent_usage.scope_key} never holds the raw id (LLD 05 section 7.4). The key
 * version that produced the hash is stored beside it ({@code consent_usage.scope_key_version}).
 *
 * <p>Key ring, mirroring the audit checkpoint key (see {@code AuditSigningKeys}): the
 * {@linkplain #LEGACY_KEY_ID first id} {@code v1} lives under {@value #KEY_NAME}; any other id
 * {@code X} under {@code consent-payment-scope-key-X}. The key is a provisioned secret: it is
 * read with {@link SecretStore#find} and is one of the {@link RequiredSecrets} the production boot
 * guard checks. A store that may generate (the dev stub) falls back to an in-process key, which
 * is lost on restart, so a dev restart forgets earlier payment checks; production never does
 * that, because the boot guard refuses such a store.
 *
 * <p>Rotation: provision the new key under its id, set {@code samanvay.consent.payment-scope.key-id}
 * to it and list the old id in {@code samanvay.consent.payment-scope.retired-key-ids}. New checks
 * are recorded under the new key; a check is also refused when the same payment was already
 * checked (used, or in flight) under a retired key, so a rotation is not a fresh start for
 * payments that were already checked. A retired key can be dropped from the list once no
 * consent that used it can still be asked for that payment.
 */
@Component
class PaymentScopeKeys implements RequiredSecrets {

    /** Key id used until one is configured. */
    static final String LEGACY_KEY_ID = "v1";

    static final String KEY_NAME = "consent-payment-scope-key";
    static final String SCOPE_PREFIX = "PAYMENT:";

    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9]{1,32}");
    private static final String HMAC = "HmacSHA256";

    /** A scope key together with the key version that produced it. */
    record Scope(String scopeKey, String keyVersion) {}

    private final SecretStore secrets;
    private final String activeKeyId;
    private final List<String> retiredKeyIds;

    @Autowired
    PaymentScopeKeys(
            SecretStore secrets,
            @Value("${samanvay.consent.payment-scope.key-id:" + LEGACY_KEY_ID + "}") String activeKeyId,
            @Value("${samanvay.consent.payment-scope.retired-key-ids:}") List<String> retiredKeyIds) {
        this.secrets = secrets;
        this.activeKeyId = validId(activeKeyId, "samanvay.consent.payment-scope.key-id");
        List<String> retired = new ArrayList<>();
        for (String id : retiredKeyIds == null ? List.<String>of() : retiredKeyIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            String trimmed = validId(id.trim(), "samanvay.consent.payment-scope.retired-key-ids");
            if (trimmed.equals(this.activeKeyId)) {
                throw new IllegalStateException("the active payment scope key id '" + trimmed + "' is also listed as retired");
            }
            retired.add(trimmed);
        }
        this.retiredKeyIds = List.copyOf(retired);
    }

    String activeKeyId() {
        return activeKeyId;
    }

    /** The scope key for {@code paymentId} under the active key. */
    Scope active(String paymentId) {
        return scope(paymentId, activeKeyId);
    }

    /** The scope keys {@code paymentId} would have had under each retired key. */
    List<Scope> retired(String paymentId) {
        return retiredKeyIds.stream().map(id -> scope(paymentId, id)).toList();
    }

    private Scope scope(String paymentId, String keyId) {
        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException("payment id is required");
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(keyBytes(keyId), HMAC));
            byte[] tag = mac.doFinal(paymentId.getBytes(StandardCharsets.UTF_8));
            return new Scope(SCOPE_PREFIX + HexFormat.of().formatHex(tag), keyId);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private byte[] keyBytes(String keyId) {
        String name = secretName(keyId);
        SecretStore.Secret secret = secrets.find(name).orElse(null);
        if (secret == null && secrets.mayGenerate()) {
            secret = secrets.resolve(name);
        }
        if (secret == null || secret.bytes() == null || secret.bytes().length == 0) {
            // Fail closed: without the key a payment check can be neither scoped nor proven unused.
            throw new IllegalStateException("payment scope key '" + name + "' is not provisioned in the SecretStore");
        }
        return secret.bytes();
    }

    static String secretName(String keyId) {
        return LEGACY_KEY_ID.equals(keyId) ? KEY_NAME : KEY_NAME + "-" + keyId;
    }

    private static String validId(String id, String property) {
        if (id == null || !KEY_ID.matcher(id).matches()) {
            throw new IllegalStateException(property + " must be 1-32 letters or digits (got '" + id + "')");
        }
        return id;
    }

    /** The active key and every retired key must be provisioned, or the production boot guard refuses to start. */
    @Override
    public List<String> keys() {
        List<String> keys = new ArrayList<>();
        keys.add(secretName(activeKeyId));
        retiredKeyIds.forEach(id -> keys.add(secretName(id)));
        return keys;
    }
}
