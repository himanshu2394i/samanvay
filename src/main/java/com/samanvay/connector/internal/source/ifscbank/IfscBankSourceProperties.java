package com.samanvay.connector.internal.source.ifscbank;

import com.samanvay.connector.internal.source.SourceMode;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the IFSC/bank source lives and how it is configured. Credentials are NOT
 * here: they come from SecretStore ({@code source-ifsc-bank-credential}, see
 * SourceCredentials) in every mode.
 *
 * @param baseUrl bank-check API root (contract v1, docs/contracts/bank-check-v1.yaml)
 * @param ifscBaseUrl public IFSC lookup root (open RBI data); defaults to baseUrl
 * @param mode required; in this PR only LIVE's credential check depends on it
 */
@ConfigurationProperties("samanvay.sources.ifsc-bank")
public record IfscBankSourceProperties(
        URI baseUrl, URI ifscBaseUrl, SourceMode mode, Duration connectTimeout, Duration readTimeout) {

    public IfscBankSourceProperties {
        if (baseUrl == null) {
            throw new IllegalArgumentException("samanvay.sources.ifsc-bank.base-url is required");
        }
        if (mode == null) {
            throw new IllegalArgumentException("samanvay.sources.ifsc-bank.mode is required (sandbox|simulator|live)");
        }
        ifscBaseUrl = ifscBaseUrl == null ? baseUrl : ifscBaseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
    }
}
