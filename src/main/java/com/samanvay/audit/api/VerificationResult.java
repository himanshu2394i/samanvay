package com.samanvay.audit.api;

public record VerificationResult(
        boolean valid, long fromSeq, long toSeq, Long failedAtSeq, String reason) {

    public static VerificationResult ok(long fromSeq, long toSeq) {
        return new VerificationResult(true, fromSeq, toSeq, null, null);
    }

    public static VerificationResult failed(long seq, String reason) {
        return new VerificationResult(false, 0, 0, seq, reason);
    }
}
