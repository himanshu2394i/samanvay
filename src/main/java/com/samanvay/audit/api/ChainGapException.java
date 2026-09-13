package com.samanvay.audit.api;

import com.samanvay.shared.SamanvayException;

public class ChainGapException extends SamanvayException {

    public ChainGapException(long missingSeq) {
        super("audit chain gap at seq " + missingSeq);
    }
}
