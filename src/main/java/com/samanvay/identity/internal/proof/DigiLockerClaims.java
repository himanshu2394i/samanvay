package com.samanvay.identity.internal.proof;

public record DigiLockerClaims(String localIdType, String localId, boolean sandboxBind) {

    static DigiLockerClaims bindToRequest() {
        return new DigiLockerClaims(null, null, true);
    }

    static DigiLockerClaims of(String localIdType, String localId) {
        return new DigiLockerClaims(localIdType, localId, false);
    }
}
