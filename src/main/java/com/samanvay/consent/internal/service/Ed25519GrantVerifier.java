package com.samanvay.consent.internal.service;

import com.samanvay.consent.api.AccessGrant;
import com.samanvay.consent.api.AccessGrantVerifier;
import com.samanvay.consent.api.InvalidGrantException;
import com.samanvay.consent.internal.repository.AccessGrantRepository;
import com.samanvay.consent.internal.repository.ConsentArtifactRepository;
import com.samanvay.shared.CanonicalJson;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.SecretStore;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class Ed25519GrantVerifier implements AccessGrantVerifier {

    static final Duration CLOCK_SKEW = Duration.ofSeconds(5);

    private final PublicKey verifyingKey;
    private final AccessGrantRepository grants;
    private final ConsentArtifactRepository consents;
    private final CanonicalJson canonicalJson;
    private final Clock clock;

    Ed25519GrantVerifier(
            SecretStore secretStore,
            AccessGrantRepository grants,
            ConsentArtifactRepository consents,
            CanonicalJson canonicalJson,
            Clock clock) {
        try {
            var secret = secretStore.resolve("consent-grant-verifying-key");
            this.verifyingKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(secret.bytes()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("verifying key unavailable", e);
        }
        this.grants = grants;
        this.consents = consents;
        this.canonicalJson = canonicalJson;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void verifyOrThrow(AccessGrant grant, DataCategory expectedCategory, String expectedConnectorRef) {
        Instant now = clock.instant();
        if (now.isAfter(grant.expiresAt().plus(CLOCK_SKEW))) {
            throw new InvalidGrantException(grant.id(), "expired");
        }
        if (!grant.category().equals(expectedCategory) || !grant.connectorRef().equals(expectedConnectorRef)) {
            throw new InvalidGrantException(grant.id(), "category/connector mismatch");
        }
        var stored = consents.findById(grant.consentId());
        if (stored.isEmpty() || stored.get().getVersion() != grant.consentVersion()) {
            throw new InvalidGrantException(grant.id(), "consent revoked or changed since issuance");
        }
        try {
            var sig = Signature.getInstance("Ed25519");
            sig.initVerify(verifyingKey);
            sig.update(canonicalJson.serialize(grant.withoutSignature()).getBytes(StandardCharsets.UTF_8));
            if (!sig.verify(grant.signature())) {
                throw new InvalidGrantException(grant.id(), "signature invalid");
            }
        } catch (GeneralSecurityException e) {
            throw new InvalidGrantException(grant.id(), "signature verification error", e);
        }
        if (grants.markUsedIfUnused(grant.nonce(), now) != 1) {
            throw new InvalidGrantException(grant.id(), "nonce already used");
        }
    }

    /** Structural: this class must never hold a signing key. */
    PublicKey verifyingKey() {
        return verifyingKey;
    }

    boolean holdsPrivateKey() {
        return false;
    }

    static boolean fieldIsNeverPrivateKey() {
        for (var field : Ed25519GrantVerifier.class.getDeclaredFields()) {
            if (PrivateKey.class.isAssignableFrom(field.getType())) {
                return false;
            }
        }
        return true;
    }
}
