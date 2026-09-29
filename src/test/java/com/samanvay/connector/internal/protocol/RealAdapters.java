package com.samanvay.connector.internal.protocol;

import com.samanvay.connector.api.ProtocolAdapter;
import com.samanvay.connector.internal.source.sftp.SftpCsvClient;
import java.util.Map;

/**
 * Test-only door to the package-private real adapters, so a test in another package (the connector
 * runtime's) can wire the REAL {@code RestAdapter}/{@code SoapAdapter}/{@code SftpCsvAdapter}, not stand-ins.
 * REST and SOAP use plain http so they can talk to an in-JVM server.
 */
public final class RealAdapters {

    /** The simulator host the real adapters must NOT be routed by. */
    public static final String MOCK_HOST = MockDepartmentBackend.HOST;

    private RealAdapters() {}

    public static ProtocolAdapter rest(DeadlineHttp http) {
        return new RestAdapter(new MockDepartmentBackend(), http, "http");
    }

    public static ProtocolAdapter soap(DeadlineHttp http) {
        return new SoapAdapter(new MockDepartmentBackend(), http, "http");
    }

    /**
     * The REST adapter as production wires it (https by default) with dev/demo department-service
     * overrides, keyed by data-source code to a base URL. Proves the override alone decides where the
     * real call goes: the catalog host of such a source is never contacted.
     */
    public static ProtocolAdapter rest(DeadlineHttp http, Map<String, String> departmentServiceUrls) {
        return new RestAdapter(new MockDepartmentBackend(), http, DepartmentServiceOverrides.of(departmentServiceUrls));
    }

    /** As {@link #rest(DeadlineHttp, Map)}, for SOAP. */
    public static ProtocolAdapter soap(DeadlineHttp http, Map<String, String> departmentServiceUrls) {
        return new SoapAdapter(new MockDepartmentBackend(), http, DepartmentServiceOverrides.of(departmentServiceUrls));
    }

    public static ProtocolAdapter sftp(SftpCsvClient client) {
        return new SftpCsvAdapter(new MockSftpStore(), client);
    }
}
