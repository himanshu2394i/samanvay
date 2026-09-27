package com.samanvay.shared;

import java.util.Collection;

/** A request body is missing a required field: 400, never a 500 from deeper down. */
public class InvalidRequestException extends SamanvayException {

    public InvalidRequestException(String message) {
        super(message);
    }

    @Override
    public String problemType() {
        return "invalid-request";
    }

    @Override
    public String reason() {
        return "INVALID_REQUEST";
    }

    public static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(field + " is required");
        }
        return value;
    }

    public static <T> T requirePresent(T value, String field) {
        if (value == null || (value instanceof Collection<?> c && c.isEmpty())) {
            throw new InvalidRequestException(field + " is required");
        }
        return value;
    }
}
