package com.samanvay.connector.internal.source.ifscbank;

import com.samanvay.connector.internal.source.SourceCredentials;
import com.samanvay.connector.internal.source.SourceMode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The client exists only once a base URL is configured; no default points
 * anywhere. A LIVE source without its credential in SecretStore stops the boot.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "samanvay.sources.ifsc-bank", name = "base-url")
@EnableConfigurationProperties(IfscBankSourceProperties.class)
class IfscBankSourceConfig {

    @Bean
    IfscBankClient ifscBankClient(IfscBankSourceProperties properties, SourceCredentials credentials) {
        if (properties.mode() == SourceMode.LIVE) {
            credentials.require(IfscBankClient.SOURCE_CODE, properties.mode());
        }
        return new IfscBankClient(properties, credentials);
    }
}
