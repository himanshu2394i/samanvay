package com.samanvay.connector.internal.source.ifscbank;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the IFSC/bank source lives and how to authenticate. This is the ONLY
 * thing that differs between the simulator and the live source; the planned
 * {@code samanvay.sources.ifsc-bank.mode=sandbox|simulator|live} switch (separate
 * PR) will just pick these values.
 *
 * @param baseUrl account-validation API root (live reference: https://api.razorpay.com)
 * @param ifscBaseUrl IFSC lookup root (live reference: https://ifsc.razorpay.com); defaults to baseUrl
 * @param keyId HTTP Basic key id
 * @param keySecret HTTP Basic key secret
 */
@ConfigurationProperties("samanvay.sources.ifsc-bank")
public record IfscBankSourceProperties(
        URI baseUrl, URI ifscBaseUrl, String keyId, String keySecret, Duration connectTimeout, Duration readTimeout) {

    public IfscBankSourceProperties {
        if (baseUrl == null) {
            throw new IllegalArgumentException("samanvay.sources.ifsc-bank.base-url is required");
        }
        ifscBaseUrl = ifscBaseUrl == null ? baseUrl : ifscBaseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
    }
}
