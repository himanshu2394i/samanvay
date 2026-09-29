package com.samanvay.connector.internal.source.sftp;

/** A real SFTP exchange failed. Messages name the source and the step, never a credential. */
public class SftpTransportException extends RuntimeException {

    public SftpTransportException(String message) {
        super(message);
    }

    public SftpTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
