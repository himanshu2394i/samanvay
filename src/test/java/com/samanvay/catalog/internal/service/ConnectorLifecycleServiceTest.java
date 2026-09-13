package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.samanvay.catalog.api.ConnectorDraft;
import com.samanvay.catalog.api.ConnectorNotReadyException;
import com.samanvay.catalog.api.ConnectorStatus;
import com.samanvay.catalog.api.ConnectorTestReport;
import com.samanvay.catalog.api.IllegalConnectorStateException;
import com.samanvay.catalog.internal.domain.ConnectorEntity;
import com.samanvay.catalog.internal.repository.ConnectorRepository;
import com.samanvay.shared.DataCategory;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConnectorLifecycleServiceTest {

    @Test
    void publishAndNewVersionRules() {
        ConnectorRepository repos = mock(ConnectorRepository.class);
        CatalogServices catalog = new CatalogServices(null, null, repos, null, null, null, e -> {});
        ConnectorEntity draft = new ConnectorEntity();
        draft.setRef("rev-income@1");
        draft.setConnectorId("rev-income");
        draft.setVersion(1);
        draft.setDataSourceCode("revenue-rest-mock");
        draft.setDataCategory("INCOME_CERTIFICATE");
        draft.setCapabilities("{}");
        draft.setInputs("[]");
        draft.setStatus("DRAFT");
        when(repos.findById("rev-income@1")).thenReturn(Optional.of(draft));
        when(repos.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repos.findByConnectorId("rev-income")).thenReturn(List.of(draft));

        assertThatThrownBy(() -> catalog.publish("rev-income@1", new ConnectorTestReport(false, List.of("fail"))))
                .isInstanceOf(ConnectorNotReadyException.class);

        assertThat(catalog.publish("rev-income@1", new ConnectorTestReport(true, List.of())).status())
                .isEqualTo(ConnectorStatus.PUBLISHED);

        draft.setStatus("PUBLISHED");
        assertThatThrownBy(() -> catalog.publish("rev-income@1", new ConnectorTestReport(true, List.of())))
                .isInstanceOf(IllegalConnectorStateException.class);

        var v2 = catalog.newVersion(
                "rev-income",
                new ConnectorDraft("rev-income", "revenue-rest-mock", DataCategory.INCOME_CERTIFICATE, "{}", "[]", 3000));
        assertThat(v2.ref()).isEqualTo("rev-income@2");
        assertThat(v2.status()).isEqualTo(ConnectorStatus.DRAFT);
    }
}
