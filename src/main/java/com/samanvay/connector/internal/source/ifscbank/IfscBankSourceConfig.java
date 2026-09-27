package com.samanvay.connector.internal.source.ifscbank;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The client exists only once a base URL is configured; no default points anywhere. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "samanvay.sources.ifsc-bank", name = "base-url")
@EnableConfigurationProperties(IfscBankSourceProperties.class)
class IfscBankSourceConfig {

    @Bean
    IfscBankClient ifscBankClient(IfscBankSourceProperties properties) {
        return new IfscBankClient(properties);
    }
}
