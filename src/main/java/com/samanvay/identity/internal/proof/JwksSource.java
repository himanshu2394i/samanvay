package com.samanvay.identity.internal.proof;

import com.nimbusds.jose.jwk.JWKSet;

/** Where a department's public signing keys come from (its published JWKS). A port so verification is testable without a network. */
public interface JwksSource {

    /**
     * The department's key set at {@code jwksUrl}. {@code forceRefresh} bypasses any cache; the verifier asks for it
     * once when it meets a key ID it does not know (the department rotated its keys).
     */
    JWKSet keys(String jwksUrl, boolean forceRefresh);
}
