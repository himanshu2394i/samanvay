package com.samanvay.tracking.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.samanvay.orchestration.api.JourneyStarted;
import com.samanvay.orchestration.api.StepCompleted;
import com.samanvay.tracking.internal.domain.ApplicationEntity;
import com.samanvay.tracking.internal.domain.StepEntity;
import com.samanvay.tracking.internal.repository.ApplicationRepository;
import com.samanvay.tracking.internal.repository.StepRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class TrackingProjectionOrderingTest {

    @Test
    void stepBeforeJourneyStartedCreatesParentThenFillsCitizen() {
        ApplicationRepository apps = Mockito.mock(ApplicationRepository.class);
        StepRepository steps = Mockito.mock(StepRepository.class);
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        UUID id = UUID.randomUUID();
        when(jdbc.queryForObject(any(String.class), Mockito.eq(Long.class))).thenReturn(1L);
        when(apps.findById(id)).thenReturn(Optional.empty());
        when(apps.saveAndFlush(any(ApplicationEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(steps.findByApplicationIdAndStepCode(id, "INCOME_CERTIFICATE")).thenReturn(Optional.empty());
        when(steps.save(any(StepEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        TrackingServices tracking = new TrackingServices(apps, steps, jdbc, ev -> {});
        tracking.on(new StepCompleted(id, "INCOME_CERTIFICATE", "VERIFIED", 9L));

        ArgumentCaptor<ApplicationEntity> parent = ArgumentCaptor.forClass(ApplicationEntity.class);
        verify(apps).saveAndFlush(parent.capture());
        assertThat(parent.getValue().getId()).isEqualTo(id);

        ArgumentCaptor<StepEntity> step = ArgumentCaptor.forClass(StepEntity.class);
        verify(steps).save(step.capture());
        assertThat(step.getValue().getApplicationId()).isEqualTo(id);

        ApplicationEntity existing = parent.getValue();
        when(apps.findById(id)).thenReturn(Optional.of(existing));
        UUID citizen = UUID.randomUUID();
        tracking.on(new JourneyStarted(id, "POST_MATRIC_SCHOLARSHIP", citizen, "proc-1", Instant.now()));
        assertThat(existing.getCitizenId()).isEqualTo(citizen);
        assertThat(existing.getJourneyCode()).isEqualTo("POST_MATRIC_SCHOLARSHIP");
        assertThat(existing.getReferenceNo()).startsWith("MH-");
    }
}
