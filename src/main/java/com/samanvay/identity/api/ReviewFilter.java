package com.samanvay.identity.api;

public record ReviewFilter(String status) {
    public static ReviewFilter pending() {
        return new ReviewFilter("PENDING");
    }
}
