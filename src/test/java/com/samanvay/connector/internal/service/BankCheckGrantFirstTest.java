package com.samanvay.connector.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.catalog.api.ConnectorCatalog;
import com.samanvay.catalog.api.SchemaCatalog;
import com.samanvay.connector.api.BankCheckAdapter;
import com.samanvay.connector.api.BankCheckAdapter.BankCheckRequest;
import com.samanvay.connector.api.BankCheckAdapters;
import com.samanvay.connector.api.DepartmentChaos;
import com.samanvay.connector.api.SourceOutcome;
import com.samanvay.connector.internal.mapping.MappingExecutor;
import com.samanvay.connector.internal.source.SourceCredentials;
import com.samanvay.connector.internal.source.SourceMode;
import com.samanvay.connector.internal.source.ifscbank.IfscBankClient;
import com.samanvay.connector.internal.source.ifscbank.IfscBankSourceProperties;
import com.samanvay.consent.api.AccessGrant;
import com.samanvay.consent.api.AccessGrantVerifier;
import com.samanvay.consent.api.InvalidGrantException;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.SecretStore;
import com.samanvay.shared.SubjectRef;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Grant first, adapter second: a grant that fails verification never looks up
 * the adapter and never reaches the source (both counters read 0). The "source"
 * is a local HTTP server behind a real IfscBankClient.
 */
class BankCheckGrantFirstTest {

    final AtomicInteger lookups = new AtomicInteger();
    final AtomicInteger sourceHits = new AtomicInteger();
    final AccessGrantVerifier verifier = mock(AccessGrantVerifier.class);
    final AuditService audit = mock(AuditService.class);
    HttpServer source;
    ConnectorRuntimeImpl runtime;

    @BeforeEach
    void setUp() throws Exception {
        source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        source.createContext("/", exchange -> {
            sourceHits.incrementAndGet();
            byte[] body = "{\"accountStatus\":\"VALID\",\"nameMatch\":\"MATCH\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        source.start();
        SecretStore store = key -> new SecretStore.Secret("k:s".getBytes(StandardCharsets.UTF_8));
        IfscBankClient client = new IfscBankClient(
                new IfscBankSourceProperties(URI.create("http://127.0.0.1:" + source.getAddress().getPort()), null,
                        SourceMode.SIMULATOR, Duration.ofSeconds(1), Duration.ofSeconds(2)),
                new SourceCredentials(store));
        BankCheckAdapters countingRegistry = code -> {
            lookups.incrementAndGet();
            return Optional.<BankCheckAdapter>of(client).filter(a -> a.sourceCode().equals(code));
        };
        runtime = new ConnectorRuntimeImpl(
                verifier, mock(ConnectorCatalog.class), mock(SchemaCatalog.class), List.of(), mock(ResilienceRegistries.class),
                mock(MappingExecutor.class), audit, mock(DepartmentChaos.class), countingRegistry);
    }

    @AfterEach
    void tearDown() {
        source.stop(0);
    }

    static AccessGrant grant() {
        return new AccessGrant(UUID.randomUUID(), new byte[0], UUID.randomUUID(), 1, new SubjectRef(UUID.randomUUID()),
                null, DataCategory.BANK_ACCOUNT, "FIN", "ifsc-bank", null, null, Instant.now(), Instant.now().plusSeconds(60),
                new byte[0]);
    }

    static final BankCheckRequest REQUEST = new BankCheckRequest("SBIN0000300", "00001000000001", "Asha Patil");

    @Test
    void failed_grant_check_never_looks_up_the_adapter_or_calls_the_source() {
        AccessGrant grant = grant();
        doThrow(new InvalidGrantException(grant.id(), "signature mismatch"))
                .when(verifier).verifyOrThrow(any(), any(), any());

        assertThatThrownBy(() -> runtime.bankCheck(grant, "ifsc-bank", REQUEST)).isInstanceOf(InvalidGrantException.class);

        assertThat(lookups).hasValue(0);
        assertThat(sourceHits).hasValue(0);
        verify(audit).record(any(AuditEntry.class));
    }

    @Test
    void passed_grant_check_looks_up_once_and_calls_the_source_once() {
        AccessGrant grant = grant();
        SourceOutcome<?> outcome = runtime.bankCheck(grant, "ifsc-bank", REQUEST);

        assertThat(outcome).isInstanceOf(SourceOutcome.Answered.class);
        verify(verifier).verifyOrThrow(eq(grant), eq(DataCategory.BANK_ACCOUNT), eq("ifsc-bank"));
        assertThat(lookups).hasValue(1);
        assertThat(sourceHits).hasValue(1);
    }

    @Test
    void unknown_source_is_a_not_configured_fault_after_the_grant_check() {
        SourceOutcome<?> outcome = runtime.bankCheck(grant(), "no-such-source", REQUEST);
        assertThat(outcome).isEqualTo(new SourceOutcome.SourceFault<>(SourceOutcome.ReasonCode.NOT_CONFIGURED, false));
        assertThat(sourceHits).hasValue(0);
    }
}
