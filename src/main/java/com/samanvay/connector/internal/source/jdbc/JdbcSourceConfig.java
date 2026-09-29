package com.samanvay.connector.internal.source.jdbc;

import com.samanvay.connector.internal.source.SourceCredentials;
import com.samanvay.connector.internal.source.SourceMode;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** A LIVE JDBC source without its credential in SecretStore stops the boot. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JdbcSourceProperties.class)
class JdbcSourceConfig {

    @Bean
    JdbcQueryClient jdbcQueryClient(JdbcSourceProperties properties, SourceCredentials credentials) {
        properties.sources().forEach((code, source) -> {
            if (source.mode() == SourceMode.LIVE) {
                credentials.require(code, source.mode());
            }
        });
        return new JdbcQueryClient(properties, credentials);
    }
}
