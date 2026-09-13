package com.samanvay.connector.internal.protocol;

import com.samanvay.connector.api.IllegalConnectorConfigurationException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class JdbcSqlGuard {
    private static final Pattern SELECT_ONLY = Pattern.compile("^\\s*SELECT\\s", Pattern.CASE_INSENSITIVE);
    private static final Set<String> FORBIDDEN =
            Set.of("INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE", "GRANT", ";--", "/*");

    private JdbcSqlGuard() {}

    static void assertSelectOnly(String sql) {
        if (sql == null || !SELECT_ONLY.matcher(sql).find() || containsForbidden(sql)) {
            throw new IllegalConnectorConfigurationException("JDBC adapter permits parameterized SELECT only");
        }
    }

    static boolean containsForbidden(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        return FORBIDDEN.stream().anyMatch(upper::contains);
    }
}
