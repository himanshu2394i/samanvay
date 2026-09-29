package com.samanvay.connector.internal.protocol;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link DepartmentServiceProperties} (empty unless the dev/demo profile sets it). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DepartmentServiceProperties.class)
class DepartmentServiceConfig {}
