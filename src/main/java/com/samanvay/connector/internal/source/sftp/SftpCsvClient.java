package com.samanvay.connector.internal.source.sftp;

import com.samanvay.connector.internal.source.SourceCredentials;
import com.samanvay.connector.internal.source.SourceCredentials.Credential;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

/**
 * Downloads a CSV file from a real SFTP server (Apache MINA sshd client).
 *
 * <p>The username and password are the {@code keyId:keySecret} pair from SecretStore (see
 * {@link SourceCredentials}); they are read on every call, so rotation needs no restart. The
 * server's host key must match the configured pinned fingerprint. Every exchange opens its own
 * client and session and closes them (try-with-resources); connect, auth, channel-open and idle
 * time are bounded by the configured timeouts, and the whole download by the read timeout.
 * Password authentication only for now; key authentication is a follow-up.
 */
public class SftpCsvClient {

    private final SftpSourceProperties properties;
    private final SourceCredentials credentials;

    public SftpCsvClient(SftpSourceProperties properties, SourceCredentials credentials) {
        this.properties = properties;
        this.credentials = credentials;
    }

    /** True when a real-transport source is configured for this data source code. */
    public boolean isConfigured(String dataSourceCode) {
        return properties.sources().containsKey(dataSourceCode);
    }

    /**
     * @param dataSourceCode picks the configuration entry
     * @param authConfigRef catalog credential reference: {@code secret:<code>} names the source
     *     whose SecretStore credential to use; anything else (e.g. {@code secret:none}) means the
     *     credential of {@code dataSourceCode}
     * @param catalogHost used when the configuration has no host
     * @param catalogPath used when the configuration has no remote path
     * @return the file content, decoded as UTF-8
     */
    public String download(String dataSourceCode, String authConfigRef, String catalogHost, String catalogPath) {
        SftpSourceProperties.Source source = properties.sources().get(dataSourceCode);
        if (source == null) {
            throw new SftpTransportException("SFTP source '" + dataSourceCode + "' is not configured (samanvay.sources.sftp.sources."
                    + dataSourceCode + ")");
        }
        String host = firstNonBlank(source.host(), catalogHost);
        String path = firstNonBlank(source.remotePath(), catalogPath);
        if (host == null || path == null) {
            throw new SftpTransportException("SFTP source '" + dataSourceCode + "' has no host or remote path");
        }
        String credentialCode = credentialCode(dataSourceCode, authConfigRef);
        Credential credential = credentials.find(credentialCode).orElseThrow(() -> new SftpTransportException(
                "SFTP source '" + dataSourceCode + "' credential is missing or malformed in SecretStore (key "
                        + SourceCredentials.secretKey(credentialCode) + ", expected username:password)"));

        Duration connectTimeout = source.connectTimeout();
        Duration readTimeout = source.readTimeout();
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.setServerKeyVerifier((session, remote, serverKey) -> KeyUtils.checkFingerPrint(source.hostKeySha256(), serverKey)
                    .getKey());
            CoreModuleProperties.AUTH_TIMEOUT.set(client, connectTimeout);
            CoreModuleProperties.IDLE_TIMEOUT.set(client, readTimeout);
            client.start();
            try (ClientSession session = client.connect(credential.keyId(), host, source.port())
                    .verify(connectTimeout)
                    .getSession()) {
                session.addPasswordIdentity(credential.keySecret());
                session.auth().verify(connectTimeout);
                try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                    return readCapped(sftp, path, source.maxBytes(), readTimeout, dataSourceCode);
                }
            } finally {
                client.stop();
            }
        } catch (SftpTransportException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // The cause is kept for diagnosis; sshd exception text never contains the password.
            throw new SftpTransportException("SFTP download failed for source '" + dataSourceCode + "' (" + host + ":"
                    + source.port() + "): " + e.getClass().getSimpleName(), e);
        }
    }

    private static String readCapped(SftpClient sftp, String path, long maxBytes, Duration total, String code) throws IOException {
        long deadline = System.nanoTime() + total.toNanos();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try (InputStream in = sftp.read(path)) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                if (out.size() > maxBytes) {
                    throw new SftpTransportException("SFTP source '" + code + "' file exceeds " + maxBytes + " bytes");
                }
                if (System.nanoTime() - deadline > 0) {
                    throw new SftpTransportException("SFTP source '" + code + "' download exceeded " + total);
                }
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    static String credentialCode(String dataSourceCode, String authConfigRef) {
        if (authConfigRef != null && authConfigRef.startsWith("secret:")) {
            String named = authConfigRef.substring("secret:".length()).trim();
            if (!named.isEmpty() && !"none".equals(named)) {
                return named;
            }
        }
        return dataSourceCode;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b != null && !b.isBlank() ? b : null;
    }
}
