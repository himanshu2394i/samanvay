package com.samanvay.orchestration.internal.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BankReviewProperties.class)
class BankReviewConfig {}
