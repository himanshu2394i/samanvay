package com.samanvay.connector.internal.source.jdbc;

/** A real JDBC source failed to connect, authenticate or query. Never carries a credential value. */
public class JdbcTransportException extends RuntimeException {
    public JdbcTransportException(String message) {
        super(message);
    }

    public JdbcTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
