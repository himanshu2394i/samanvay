package com.samanvay.identity.api;

import com.samanvay.shared.SamanvayException;

public class DuplicateLocalIdException extends SamanvayException {
    public DuplicateLocalIdException() {
        super("Department local id already linked to another citizen");
    }

    @Override
    public int status() {
        return 409;
    }

    @Override
    public String problemType() {
        return "identity/duplicate-local-id";
    }

    @Override
    public String reason() {
        return "DUPLICATE_LOCAL_ID";
    }
}
