package com.samanvay.tracking.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.samanvay.orchestration.api.JourneyStarted;
import com.samanvay.orchestration.api.StepCompleted;
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
    void stepBeforeJourneyStartedUpsertsParentWithoutAborting() {
        ApplicationRepository apps = Mockito.mock(ApplicationRepository.class);
        StepRepository steps = Mockito.mock(StepRepository.class);
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        UUID id = UUID.randomUUID();
        when(jdbc.queryForObject(any(String.class), Mockito.eq(Long.class))).thenReturn(1L);
        when(jdbc.queryForObject(any(String.class), Mockito.eq(String.class), any())).thenReturn("MH-APP-2026-000001");
        when(steps.findByApplicationIdAndStepCode(id, "INCOME_CERTIFICATE")).thenReturn(Optional.empty());
        when(steps.save(any(StepEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        TrackingServices tracking = new TrackingServices(apps, steps, jdbc, ev -> {});
        tracking.on(new StepCompleted(id, "INCOME_CERTIFICATE", "VERIFIED", 9L));

        verify(jdbc).update(contains("ON CONFLICT (id) DO NOTHING"), any(), any(), any());
        ArgumentCaptor<StepEntity> step = ArgumentCaptor.forClass(StepEntity.class);
        verify(steps).save(step.capture());
        assertThat(step.getValue().getApplicationId()).isEqualTo(id);

        UUID citizen = UUID.randomUUID();
        tracking.on(new JourneyStarted(id, "POST_MATRIC_SCHOLARSHIP", citizen, "proc-1", Instant.now()));
        verify(jdbc).update(contains("ON CONFLICT (id) DO UPDATE"), any(), any(), any(), any(), any(), any());
    }
}
