package com.samanvay.orchestration.internal.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes passbook files past the retention window (default 30 days); the hash and decision stay. */
@Component
class BankReviewRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(BankReviewRetentionJob.class);

    private final BankReviewService reviews;

    BankReviewRetentionJob(BankReviewService reviews) {
        this.reviews = reviews;
    }

    @Scheduled(cron = "0 30 3 * * *")
    void purge() {
        int purged = reviews.purgeExpiredDocuments();
        if (purged > 0) {
            log.info("bank-review retention: purged {} passbook file(s)", purged);
        }
    }
}
