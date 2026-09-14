package com.samanvay.identity.api;

public interface LinkProofProvider {

    LinkProofKind kind();

    String label();

    VerifiedLocalId verify(AuthProof proof, LinkProofContext context);
}
