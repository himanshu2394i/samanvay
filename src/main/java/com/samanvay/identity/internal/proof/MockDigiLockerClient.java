package com.samanvay.identity.internal.proof;

import com.samanvay.identity.api.LinkProofInvalidException;
import org.springframework.stereotype.Component;

@Component
class MockDigiLockerClient implements DigiLockerClient {

    @Override
    public DigiLockerClaims redeem(String authorizationCode) {
        if (authorizationCode == null || authorizationCode.isBlank()) {
            throw new LinkProofInvalidException();
        }
        if ("sandbox".equals(authorizationCode)) {
            return DigiLockerClaims.bindToRequest();
        }
        if (authorizationCode.startsWith("sandbox:")) {
            String[] parts = authorizationCode.split(":", 3);
            if (parts.length != 3 || parts[1].isBlank() || parts[2].isBlank()) {
                throw new LinkProofInvalidException();
            }
            return DigiLockerClaims.of(parts[1], parts[2]);
        }
        throw new LinkProofInvalidException();
    }
}
