package com.samanvay.consent.internal.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param retention how long a consent record is kept after it ends (revoked or expired) before
 *     the purge job deletes it; default 7 years (platform policy, not a legal claim). Audit rows
 *     are never purged.
 */
@ConfigurationProperties("samanvay.consent")
public record ConsentRetentionProperties(Duration retention) {

    public ConsentRetentionProperties {
        retention = retention == null ? Duration.ofDays(2555) : retention;
    }
}
