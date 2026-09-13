package com.samanvay.tracking.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.orchestration.api.StepPendingSource;
import com.samanvay.tracking.internal.domain.ApplicationEntity;
import com.samanvay.tracking.internal.repository.ApplicationRepository;
import com.samanvay.tracking.internal.repository.StepRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class NeverFailedWhilePartialTest {

    @Test
    void pendingSourceIsPartiallyVerified() {
        ApplicationRepository apps = Mockito.mock(ApplicationRepository.class);
        ApplicationEntity e = new ApplicationEntity();
        e.setId(UUID.randomUUID());
        e.setStatus("SUBMITTED");
        Mockito.when(apps.findById(e.getId())).thenReturn(Optional.of(e));
        Mockito.when(apps.save(e)).thenReturn(e);
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        Mockito.when(jdbc.queryForObject(Mockito.anyString(), Mockito.eq(Long.class))).thenReturn(1L);
        TrackingServices tracking = new TrackingServices(apps, Mockito.mock(StepRepository.class), jdbc, ev -> {});
        tracking.on(new StepPendingSource(e.getId(), "INCOME_CERTIFICATE", 1, Instant.now()));
        assertThat(e.getStatus()).isEqualTo("PARTIALLY_VERIFIED");
        assertThat(e.getStatus()).isNotEqualTo("FAILED");
    }
}
