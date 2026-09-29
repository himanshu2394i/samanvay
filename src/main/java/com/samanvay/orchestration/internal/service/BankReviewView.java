package com.samanvay.orchestration.internal.service;

import com.samanvay.connector.api.BankCheckReview;
import com.samanvay.orchestration.internal.domain.BankReviewEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * What an officer sees for one review. No bank holder name — only the masked
 * account, a plain reason, the matcher version, and whether a document is in.
 */
public record BankReviewView(
        UUID id,
        String applicationId,
        String accountMasked,
        String reason,
        String reasonText,
        String matcherVersion,
        String status,
        boolean hasDocument,
        Instant createdAt) {

    static BankReviewView of(BankReviewEntity e) {
        return new BankReviewView(
                e.getId(),
                e.getApplicationId(),
                e.getAccountMasked(),
                e.getReviewReason(),
                officerText(e.getReviewReason()),
                e.getMatcherVersion(),
                e.getStatus(),
                e.getDocumentHash() != null,
                e.getCreatedAt());
    }

    /** Plain officer-facing reason. The citizen never sees "no match" (that copy is a follow-up). */
    static String officerText(String reason) {
        BankCheckReview.Reason r;
        try {
            r = BankCheckReview.Reason.valueOf(reason);
        } catch (IllegalArgumentException e) {
            return reason;
        }
        return switch (r) {
            case NAME_MATCHED -> "The name matched";
            case NAME_PARTIAL -> "Only part of the name matched";
            case NAME_NOT_MATCHED -> "The name did not match";
            case NAME_NOT_COMPARED -> "The bank's name could not be compared automatically";
            case ACCOUNT_NOT_USABLE -> "The bank account is closed or invalid";
        };
    }
}
