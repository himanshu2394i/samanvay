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
import com.samanvay.connector.api.BankCheckAdapter.AccountStatus;
import com.samanvay.connector.api.BankCheckAdapter.BankCheckAnswer;
import com.samanvay.connector.api.BankCheckAdapter.BankCheckRequest;
import com.samanvay.connector.api.BankCheckAdapter.IfscAnswer;
import com.samanvay.connector.api.BankCheckAdapter.NameMatch;
import com.samanvay.connector.api.BankCheckAdapters;
import com.samanvay.connector.api.DepartmentChaos;
import com.samanvay.connector.api.SourceOutcome;
import com.samanvay.connector.api.SourceOutcome.Answered;
import com.samanvay.connector.api.SourceOutcome.ReasonCode;
import com.samanvay.connector.api.SourceOutcome.SourceFault;
import com.samanvay.connector.api.SourceOutcome.SourceTimeout;
import com.samanvay.connector.internal.mapping.MappingExecutor;
import com.samanvay.consent.api.AccessGrant;
import com.samanvay.consent.api.AccessGrantVerifier;
import com.samanvay.shared.DataCategory;
import com.samanvay.shared.SubjectRef;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A bank check that the source answers records exactly one {@code DATA_ACCESSED}
 * row; a failed call (timeout, fault) records none. The row never carries the
 * holder's name (it isn't returned by the source) nor the account number.
 */
class BankCheckDataAccessedAuditTest {

    static final BankCheckRequest REQUEST = new BankCheckRequest("SBIN0000300", "00001000000001", "Asha Patil");

    final AccessGrantVerifier verifier = mock(AccessGrantVerifier.class);
    final AuditService audit = mock(AuditService.class);

    ConnectorRuntimeImpl runtimeWith(SourceOutcome<BankCheckAnswer> outcome) {
        BankCheckAdapter adapter = new BankCheckAdapter() {
            @Override public String sourceCode() { return "ifsc-bank"; }
            @Override public SourceOutcome<IfscAnswer> lookupIfsc(String ifsc) { return new Answered<>(new IfscAnswer(Optional.empty()), false); }
            @Override public SourceOutcome<BankCheckAnswer> check(BankCheckRequest request) { return outcome; }
        };
        BankCheckAdapters registry = code -> Optional.of(adapter).filter(a -> a.sourceCode().equals(code));
        return new ConnectorRuntimeImpl(
                verifier, mock(ConnectorCatalog.class), mock(SchemaCatalog.class), List.of(),
                mock(ResilienceRegistries.class), mock(MappingExecutor.class), audit, mock(DepartmentChaos.class), java.time.Duration.ofSeconds(10), registry);
    }

    static AccessGrant grant() {
        return new AccessGrant(UUID.randomUUID(), new byte[0], UUID.randomUUID(), 1, new SubjectRef(UUID.randomUUID()),
                null, DataCategory.BANK_ACCOUNT, "FIN", "ifsc-bank", null, null, Instant.now(),
                Instant.now().plusSeconds(60), new byte[0]);
    }

    @Test
    void an_answered_bank_check_records_one_data_accessed_row_without_personal_data() {
        AccessGrant grant = grant();
        runtimeWith(new Answered<>(new BankCheckAnswer(AccountStatus.VALID, NameMatch.MATCH), false))
                .bankCheck(grant, "ifsc-bank", REQUEST);

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(captor.capture());
        AuditEntry entry = captor.getValue();
        assertThat(entry.action()).isEqualTo("DATA_ACCESSED");
        assertThat(entry.outcome()).isEqualTo(Outcome.ALLOWED);
        assertThat(entry.resource()).isEqualTo("ifsc-bank");
        assertThat(entry.toString()).doesNotContain("Asha Patil").doesNotContain("00001000000001");
    }

    @Test
    void a_timeout_records_no_data_accessed_row() {
        runtimeWith(new SourceTimeout<>()).bankCheck(grant(), "ifsc-bank", REQUEST);
        verify(audit, never()).record(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void a_fault_records_no_data_accessed_row() {
        runtimeWith(new SourceFault<>(ReasonCode.SERVER_ERROR, false)).bankCheck(grant(), "ifsc-bank", REQUEST);
        verify(audit, never()).record(org.mockito.ArgumentMatchers.any());
    }
}
