package com.samanvay.identity.api;

import com.samanvay.shared.SamanvayException;

public class LinkProofInvalidException extends SamanvayException {
    public LinkProofInvalidException() {
        super("Department identity proof is invalid");
    }

    @Override
    public int status() {
        return 401;
    }

    @Override
    public String problemType() {
        return "identity/link-proof-invalid";
    }

    @Override
    public String reason() {
        return "LINK_PROOF_INVALID";
    }
}
