package com.samanvay.orchestration.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.samanvay.audit.api.ActorType;
import com.samanvay.audit.api.AuditEntry;
import com.samanvay.audit.api.AuditService;
import com.samanvay.orchestration.api.ApplicationNotApprovableException;
import com.samanvay.orchestration.api.ApplicationStateChanged;
import com.samanvay.orchestration.api.InstanceNotFoundException;
import com.samanvay.orchestration.internal.domain.InstanceEntity;
import com.samanvay.orchestration.internal.repository.InstanceRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

class ApplicationApprovalServiceTest {

    private static final String MOVE = "UPDATE orchestration_instance SET status = ? WHERE id = ? AND status = ?";
    private static final String READ = "SELECT status FROM orchestration_instance WHERE id = ?";

    private final InstanceRepository instances = mock(InstanceRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ApplicationApprovalService service = new ApplicationApprovalService(instances, jdbc, audit, events);

    private final UUID app = UUID.randomUUID();
    private final UUID citizen = UUID.randomUUID();

    private void instanceExists() {
        InstanceEntity e = new InstanceEntity();
        e.setId(app);
        e.setCitizenId(citizen);
        e.setJourneyCode("SOME_JOURNEY");
        when(instances.findById(app)).thenReturn(Optional.of(e));
    }

    @Test
    void verifiedBecomesApprovedPublishesTheEventAndAudits() {
        instanceExists();
        when(jdbc.update(MOVE, "APPROVED", app, "VERIFIED")).thenReturn(1);

        assertThat(service.approve(app, "officer-7")).isEqualTo("APPROVED");

        verify(events).publishEvent(new ApplicationStateChanged(app, "APPROVED"));
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo("APPLICATION_APPROVED");
        assertThat(entry.getValue().actorType()).isEqualTo(ActorType.OFFICER);
        assertThat(entry.getValue().actorId()).isEqualTo("officer-7");
        assertThat(entry.getValue().subjectId()).isEqualTo(citizen.toString());
        assertThat(entry.getValue().meta())
                .containsEntry("applicationId", app.toString())
                .containsEntry("journeyCode", "SOME_JOURNEY");
    }

    @Test
    void approvingAnApprovedApplicationIsANoOp() {
        instanceExists();
        when(jdbc.update(MOVE, "APPROVED", app, "VERIFIED")).thenReturn(0);
        when(jdbc.queryForObject(READ, String.class, app)).thenReturn("APPROVED");

        assertThat(service.approve(app, "officer-7")).isEqualTo("APPROVED");

        verifyNoInteractions(events, audit);
    }

    @Test
    void onlyVerifiedIsApprovable() {
        for (String status : new String[] {"SUBMITTED", "PARTIALLY_VERIFIED", "REJECTED", "CLOSED"}) {
            instanceExists();
            when(jdbc.update(MOVE, "APPROVED", app, "VERIFIED")).thenReturn(0);
            when(jdbc.queryForObject(READ, String.class, app)).thenReturn(status);

            assertThatThrownBy(() -> service.approve(app, "officer-7"))
                    .isInstanceOf(ApplicationNotApprovableException.class)
                    .hasMessageContaining(status);
        }
        verify(events, never()).publishEvent(any(Object.class));
        verifyNoInteractions(audit);
    }

    @Test
    void anUnknownApplicationIs404() {
        when(instances.findById(app)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.approve(app, "officer-7")).isInstanceOf(InstanceNotFoundException.class);
        verify(jdbc, never()).update(eq(MOVE), any(), any(), any());
        verifyNoInteractions(events, audit);
    }

    @Test
    void notApprovableIs409() {
        assertThat(new ApplicationNotApprovableException(app, "REJECTED").status()).isEqualTo(409);
    }
}
