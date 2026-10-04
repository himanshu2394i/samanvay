package com.samanvay.identity.internal.service;

import java.sql.SQLException;
import org.springframework.dao.DataIntegrityViolationException;

/** Tells a unique-key collision (Postgres 23505) from any other integrity error, so only the first is reported as "duplicate". */
final class UniqueViolation {

    private UniqueViolation() {}

    static boolean is(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
