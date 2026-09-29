package com.samanvay.shared;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exposes a configured {@link NameMatcher} for adapters to inject. */
@Configuration
@EnableConfigurationProperties(NameMatchProperties.class)
class NameMatchConfig {

    @Bean
    NameMatcher nameMatcher(NameMatchProperties properties) {
        return new NameMatcher(properties.honorificsOrDefault());
    }
}
