package com.samanvay.connector.internal.source.sftp;

import com.samanvay.connector.internal.source.SourceMode;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Real SFTP sources, keyed by data-source code, under
 * {@code samanvay.sources.sftp.sources.<code>}. Credentials are NOT here: they come from
 * SecretStore ({@code source-<code>-credential}, value {@code username:password}, see
 * SourceCredentials) in every mode.
 *
 * <p>Example:
 * <pre>
 * samanvay.sources.sftp.sources.municipal-sftp:
 *   mode: live
 *   host: sftp.municipal.example
 *   port: 22
 *   remote-path: /outbound/property.csv
 *   host-key-sha256: "SHA256:..."   # pinned server key, as printed by ssh-keygen -lf
 * </pre>
 *
 * <p>No default points anywhere: a data source that is not the simulator host and has no entry
 * here is refused, not guessed.
 */
@ConfigurationProperties("samanvay.sources.sftp")
public record SftpSourceProperties(Map<String, Source> sources) {

    public SftpSourceProperties {
        sources = sources == null ? Map.of() : Map.copyOf(sources);
    }

    /**
     * @param mode required (sandbox|simulator|live); LIVE requires the credential at boot
     * @param host falls back to the catalog host of the data source when blank
     * @param port defaults to 22
     * @param remotePath the CSV file; falls back to the catalog endpoint when blank
     * @param hostKeySha256 required: the server's public-key fingerprint ({@code SHA256:...}).
     *     A mismatching server key is refused, so there is no trust-on-first-use and no accept-all.
     * @param maxBytes upper bound on the downloaded file (default 10 MiB)
     */
    public record Source(
            SourceMode mode,
            String host,
            Integer port,
            String remotePath,
            String hostKeySha256,
            Duration connectTimeout,
            Duration readTimeout,
            Long maxBytes) {

        public Source {
            if (mode == null) {
                throw new IllegalArgumentException("samanvay.sources.sftp.sources.<code>.mode is required (sandbox|simulator|live)");
            }
            if (hostKeySha256 == null || hostKeySha256.isBlank()) {
                throw new IllegalArgumentException("samanvay.sources.sftp.sources.<code>.host-key-sha256 is required (SHA256:...)");
            }
            port = port == null ? 22 : port;
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
            maxBytes = maxBytes == null ? 10L * 1024 * 1024 : maxBytes;
        }
    }
}
