package com.samanvay.identity.internal.proof;

/**
 * Meri Pehchaan / API Setu plug point. A LIVE client needs partner credentials; this
 * codebase ships only a sandbox mock.
 */
public interface DigiLockerClient {

    DigiLockerClaims redeem(String authorizationCode);
}
