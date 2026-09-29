package com.samanvay.consent.internal.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Marks ACTIVE consents past their {@code valid_until} as EXPIRED, in batches. */
@Component
class ConsentExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ConsentExpiryJob.class);

    private final ConsentServices consents;

    ConsentExpiryJob(ConsentServices consents) {
        this.consents = consents;
    }

    @Scheduled(cron = "0 15 3 * * *")
    void markExpired() {
        int total = 0;
        int batch;
        do {
            batch = consents.markExpired();
            total += batch;
        } while (batch == ConsentServices.EXPIRY_BATCH);
        if (total > 0) {
            log.info("consent expiry: marked {} consent(s) EXPIRED", total);
        }
    }
}
