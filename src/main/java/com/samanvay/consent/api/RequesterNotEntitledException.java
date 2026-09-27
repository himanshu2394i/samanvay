package com.samanvay.consent.api;

import com.samanvay.shared.SamanvayException;

/** The requester is not the department that the catalog purpose belongs to. */
public class RequesterNotEntitledException extends SamanvayException {

    public RequesterNotEntitledException(String requester, String purposeCode) {
        super("Requester " + requester + " may not request consent for purpose " + purposeCode);
    }

    @Override
    public int status() {
        return 403;
    }

    @Override
    public String problemType() {
        return "consent/requester-not-entitled";
    }

    @Override
    public String reason() {
        return "REQUESTER_NOT_ENTITLED";
    }
}
