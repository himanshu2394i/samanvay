package com.samanvay.orchestration.internal.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where passbook uploads live and how long the file is kept.
 *
 * @param uploadDir directory for uploaded files; defaults to a temp subfolder
 * @param retention how long an undecided review's file is kept before the purge job
 *     deletes it (the hash and decision are always kept); default 30 days
 */
@ConfigurationProperties("samanvay.bank-review")
public record BankReviewProperties(String uploadDir, Duration retention) {

    public BankReviewProperties {
        if (uploadDir == null || uploadDir.isBlank()) {
            uploadDir = System.getProperty("java.io.tmpdir") + "/samanvay-passbooks";
        }
        retention = retention == null ? Duration.ofDays(30) : retention;
    }
}
