package com.samanvay.consent.internal.service;

import com.samanvay.consent.api.GrantSigningException;
import com.samanvay.consent.api.UnsignedGrant;
import com.samanvay.shared.CanonicalJson;
import com.samanvay.shared.SecretStore;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import org.springframework.stereotype.Component;

@Component
public class GrantSigner {

    private final PrivateKey signingKey;
    private final CanonicalJson canonicalJson;

    GrantSigner(SecretStore secretStore, CanonicalJson canonicalJson) {
        try {
            var secret = secretStore.resolve("consent-grant-signing-key");
            this.signingKey = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(secret.bytes()));
        } catch (GeneralSecurityException e) {
            throw new GrantSigningException(e);
        }
        this.canonicalJson = canonicalJson;
    }

    byte[] sign(UnsignedGrant grant) {
        try {
            var sig = Signature.getInstance("Ed25519");
            sig.initSign(signingKey);
            sig.update(canonicalJson.serialize(grant).getBytes(StandardCharsets.UTF_8));
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw new GrantSigningException(e);
        }
    }
}
