package com.samanvay.consent.api;

import com.samanvay.shared.SamanvayException;

/** A department's signed consent statement was not accepted. One message for every cause: the reason is only logged. */
public class ConsentStatementInvalidException extends SamanvayException {

    public ConsentStatementInvalidException() {
        super("The consent statement could not be accepted");
    }

    @Override
    public int status() {
        return 400;
    }

    @Override
    public String problemType() {
        return "consent/statement-invalid";
    }

    @Override
    public String reason() {
        return "CONSENT_STATEMENT_INVALID";
    }
}
