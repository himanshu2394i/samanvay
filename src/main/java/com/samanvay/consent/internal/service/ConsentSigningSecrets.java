package com.samanvay.consent.internal.service;

import com.samanvay.shared.RequiredSecrets;
import java.util.List;
import org.springframework.stereotype.Component;

/** The consent grant key pair {@link GrantSigner} and {@link Ed25519GrantVerifier} resolve at boot. */
@Component
class ConsentSigningSecrets implements RequiredSecrets {

    static final String SIGNING_KEY = "consent-grant-signing-key";
    static final String VERIFYING_KEY = "consent-grant-verifying-key";

    @Override
    public List<String> keys() {
        return List.of(SIGNING_KEY, VERIFYING_KEY);
    }
}
