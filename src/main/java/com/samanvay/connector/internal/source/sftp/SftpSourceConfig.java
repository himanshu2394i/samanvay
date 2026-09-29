package com.samanvay.connector.internal.source.sftp;

import com.samanvay.connector.internal.source.SourceCredentials;
import com.samanvay.connector.internal.source.SourceMode;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** A LIVE SFTP source without its credential in SecretStore stops the boot. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SftpSourceProperties.class)
class SftpSourceConfig {

    @Bean
    SftpCsvClient sftpCsvClient(SftpSourceProperties properties, SourceCredentials credentials) {
        properties.sources().forEach((code, source) -> {
            if (source.mode() == SourceMode.LIVE) {
                credentials.require(code, source.mode());
            }
        });
        return new SftpCsvClient(properties, credentials);
    }
}
