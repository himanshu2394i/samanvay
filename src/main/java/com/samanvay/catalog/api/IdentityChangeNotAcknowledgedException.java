package com.samanvay.catalog.api;

import com.samanvay.shared.SamanvayException;

/**
 * A manifest (or a plain catalog write) would change the identity of a department that already has one: its login and key
 * addresses, assertion issuer, person-ID type, or the key that signs its manifest. That decides whose word Samanvay takes for "this
 * person is X", so it is never done silently: the admin must say so explicitly ({@code acknowledgeIdentityChange}).
 */
public class IdentityChangeNotAcknowledgedException extends SamanvayException {

    public IdentityChangeNotAcknowledgedException(String message) {
        super(message);
    }

    @Override
    public int status() {
        return 409;
    }

    @Override
    public String problemType() {
        return "catalog/identity-change-not-acknowledged";
    }

    @Override
    public String reason() {
        return "IDENTITY_CHANGE_NOT_ACKNOWLEDGED";
    }
}
