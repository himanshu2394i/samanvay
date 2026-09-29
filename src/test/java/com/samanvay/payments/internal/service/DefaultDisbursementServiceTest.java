package com.samanvay.payments.internal.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.samanvay.audit.api.AuditService;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DefaultDisbursementServiceTest {

    private static DefaultDisbursementService service(int instalments) {
        return new DefaultDisbursementService(mock(JdbcTemplate.class), mock(AuditService.class), Clock.systemUTC(), instalments);
    }

    @Test
    void theInstalmentCountMustBeBetweenOneAndTwelve() {
        service(1);
        service(12);
        assertThatThrownBy(() -> service(0)).isInstanceOf(IllegalStateException.class).hasMessageContaining("samanvay.payments.instalments");
        assertThatThrownBy(() -> service(13)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anApplicationCitizenAndJourneyAreRequired() {
        var service = service(2);
        assertThatThrownBy(() -> service.disburse(null, UUID.randomUUID(), "J")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.disburse(UUID.randomUUID(), null, "J")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.disburse(UUID.randomUUID(), UUID.randomUUID(), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
