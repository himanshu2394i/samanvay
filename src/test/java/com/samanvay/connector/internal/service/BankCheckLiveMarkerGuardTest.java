package com.samanvay.connector.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.audit.api.Outcome;
import com.samanvay.catalog.api.ConnectorCatalog;
import com.samanvay.catalog.api.SchemaCatalog;
import com.samanvay.connector.api.BankCheckAdapter;
import com.samanvay.connector.api.BankCheckAdapter.BankCheckRequest;
import com.samanvay.connector.api.BankCheckAdapters;
import com.samanvay.connector.api.DepartmentChaos;
import com.samanvay.connector.api.SourceOutcome;
import com.samanvay.connector.api.SourceOutcome.ReasonCode;
import com.samanvay.connector.internal.mapping.MappingExecutor;
import com.samanvay.connector.internal.source.SourceCredentials;
import com.samanvay.connector.internal.source.SourceMode;
import com.samanvay.connector.internal.source.ifscbank.IfscBankClient;
import com.samanvay.connector.internal.source.ifscbank.IfscBankSourceProperties;
import com.samanvay.consent.api.AccessGrant;
import com.samanvay.consent.api.AccessGrantVerifier;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The marker cross-check: a LIVE source must never return the simulator marker.
 * When it does, the answer is refused ({@link ReasonCode#MARKER_IN_LIVE_MODE}),
 * audited ({@code LIVE_SOURCE_MARKER_REJECTED}) and alarmed (ERROR log) — the
 * answer is never returned. In simulator mode the marker is expected and passes
 * through untouched.
 */
class BankCheckLiveMarkerGuardTest {

    static final BankCheckRequest REQUEST = new BankCheckRequest("SBIN0000300", "00001000000001", "Asha Patil");

    final AtomicInteger sourceHits = new AtomicInteger();
    final AccessGrantVerifier verifier = mock(AccessGrantVerifier.class);
    final AuditService audit = mock(AuditService.class);
    HttpServer source;

    void startSource(boolean withMarker) throws Exception {
        source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        source.createContext("/", exchange -> {
            sourceHits.incrementAndGet();
            byte[] body = "{\"accountStatus\":\"VALID\",\"nameMatch\":\"MATCH\"}".getBytes(StandardCharsets.UTF_8);
            if (withMarker) {
                exchange.getResponseHeaders().add(IfscBankClient.MARKER_HEADER, "true");
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        source.start();
    }

    ConnectorRuntimeImpl runtimeFor(SourceMode mode) {
        SecretStore store = key -> new SecretStore.Secret("k:s".getBytes(StandardCharsets.UTF_8));
        IfscBankClient client = new IfscBankClient(
                new IfscBankSourceProperties(URI.create("http://127.0.0.1:" + source.getAddress().getPort()), null,
                        mode, Duration.ofSeconds(1), Duration.ofSeconds(2)),
                new SourceCredentials(store));
        BankCheckAdapters registry =
                code -> Optional.<BankCheckAdapter>of(client).filter(a -> a.sourceCode().equals(code));
        return new ConnectorRuntimeImpl(
                verifier, mock(ConnectorCatalog.class), mock(SchemaCatalog.class), List.of(),
                mock(ResilienceRegistries.class), mock(MappingExecutor.class), audit, mock(DepartmentChaos.class), Duration.ofSeconds(10), registry);
    }

    @AfterEach
    void tearDown() {
        if (source != null) source.stop(0);
    }

    static AccessGrant grant() {
        return new AccessGrant(UUID.randomUUID(), new byte[0], UUID.randomUUID(), 1, new SubjectRef(UUID.randomUUID()),
                null, DataCategory.BANK_ACCOUNT, "FIN", "ifsc-bank", null, null, Instant.now(),
                Instant.now().plusSeconds(60), new byte[0]);
    }

    @Test
    void live_source_returning_the_marker_is_refused_and_audited() throws Exception {
        startSource(true);
        SourceOutcome<?> outcome = runtimeFor(SourceMode.LIVE).bankCheck(grant(), "ifsc-bank", REQUEST);

        assertThat(outcome).isEqualTo(new SourceOutcome.SourceFault<>(ReasonCode.MARKER_IN_LIVE_MODE, true));
        assertThat(sourceHits).hasValue(1);

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(captor.capture());
        AuditEntry entry = captor.getValue();
        assertThat(entry.action()).isEqualTo("LIVE_SOURCE_MARKER_REJECTED");
        assertThat(entry.outcome()).isEqualTo(Outcome.DENIED);
        assertThat(entry.resource()).isEqualTo("ifsc-bank");
    }

    @Test
    void simulator_source_returning_the_marker_passes_through_unaudited() throws Exception {
        startSource(true);
        SourceOutcome<?> outcome = runtimeFor(SourceMode.SIMULATOR).bankCheck(grant(), "ifsc-bank", REQUEST);

        assertThat(outcome).isInstanceOf(SourceOutcome.Answered.class);
        assertThat(outcome.simulatorMarker()).isTrue();
        // The answer passes through: it is a normal access (DATA_ACCESSED), not a marker refusal.
        assertThat(onlyAuditAction()).isEqualTo("DATA_ACCESSED");
    }

    @Test
    void live_source_without_the_marker_passes_through() throws Exception {
        startSource(false);
        SourceOutcome<?> outcome = runtimeFor(SourceMode.LIVE).bankCheck(grant(), "ifsc-bank", REQUEST);

        assertThat(outcome).isInstanceOf(SourceOutcome.Answered.class);
        assertThat(outcome.simulatorMarker()).isFalse();
        assertThat(onlyAuditAction()).isEqualTo("DATA_ACCESSED");
    }

    private String onlyAuditAction() {
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(captor.capture());
        return captor.getValue().action();
    }
}
