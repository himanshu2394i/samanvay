package com.samanvay.consent.api;

import com.samanvay.shared.SamanvayException;

/** The requester is not the department that approved the citizen's prior-year award. */
public class NotAwardingDepartmentException extends SamanvayException {

    public NotAwardingDepartmentException(String requester, String purposeCode) {
        super("Requester " + requester + " did not approve the prior award that purpose " + purposeCode + " needs");
    }

    @Override
    public int status() {
        return 403;
    }

    @Override
    public String problemType() {
        return "consent/not-awarding-department";
    }

    @Override
    public String reason() {
        return "NOT_AWARDING_DEPARTMENT";
    }
}
