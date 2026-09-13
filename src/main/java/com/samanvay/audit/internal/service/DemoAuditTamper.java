package com.samanvay.audit.internal.service;

import java.sql.DriverManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Demo-only: rewrites one audit row using the migrate role (the app role cannot UPDATE).
 * Proves beat 6 — verification fails after a live edit. Not a production mutation API.
 */
@Profile("demo")
@Component
public class DemoAuditTamper {

    private final String url;
    private final String user;
    private final String password;

    DemoAuditTamper(
            @Value("${spring.flyway.url}") String url,
            @Value("${spring.flyway.user}") String user,
            @Value("${spring.flyway.password}") String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public void rewriteReason(long seq, String reason) {
        try (var conn = DriverManager.getConnection(url, user, password);
                var ps = conn.prepareStatement("UPDATE audit.audit_entry SET reason = ? WHERE seq = ?")) {
            ps.setString(1, reason);
            ps.setLong(2, seq);
            if (ps.executeUpdate() != 1) {
                throw new IllegalArgumentException("no audit row " + seq);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("demo tamper failed", e);
        }
    }
}
