package com.samanvay.consent.internal.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes consent records past the retention window (default 7 years after they ended); audit rows stay. */
@Component
class ConsentRetentionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(ConsentRetentionPurgeJob.class);

    private final ConsentServices consents;
    private final ConsentRetentionProperties properties;

    ConsentRetentionPurgeJob(ConsentServices consents, ConsentRetentionProperties properties) {
        this.consents = consents;
        this.properties = properties;
    }

    @Scheduled(cron = "0 45 3 * * *")
    void purge() {
        int purged = consents.purgeEndedRecords(properties.retention());
        if (purged > 0) {
            log.info("consent retention: purged {} consent record(s)", purged);
        }
    }
}
