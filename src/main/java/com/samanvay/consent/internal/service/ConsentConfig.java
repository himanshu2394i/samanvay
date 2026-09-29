package com.samanvay.consent.internal.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ConsentRetentionProperties.class)
class ConsentConfig {}
