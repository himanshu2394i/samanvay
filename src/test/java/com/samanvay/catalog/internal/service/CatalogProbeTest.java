package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.samanvay.catalog.api.DataSourceHealth;
import com.samanvay.catalog.internal.domain.DataSourceEntity;
import com.samanvay.catalog.internal.repository.DataSourceRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The health probe goes where the real calls go: the dev/demo override if there is one, else the registered host, which must still be public. */
class CatalogProbeTest {

    HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private CatalogServices catalog(DataSourceEntity entity, MockEnvironment env, List<String> exempt) {
        DataSourceRepository repo = mock(DataSourceRepository.class);
        when(repo.findById(entity.getCode())).thenReturn(Optional.of(entity));
        when(repo.save(any(DataSourceEntity.class))).thenAnswer(i -> i.getArgument(0));
        return new CatalogServices(null, repo, null, null, null, null, null, e -> {}, exempt, env);
    }

    private DataSourceEntity source(String host) {
        DataSourceEntity e = new DataSourceEntity();
        e.setCode("probe-src");
        e.setDepartmentCode("D");
        e.setProtocol("REST");
        e.setBaseHost(host);
        return e;
    }

    @Test
    void a_source_pointed_at_a_local_department_service_is_probed_there_not_at_its_registered_host() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        MockEnvironment env = new MockEnvironment().withProperty("samanvay.sources.department-service.urls.probe-src", "http://127.0.0.1:" + server.getAddress().getPort());

        DataSourceHealth h = catalog(source("dept.invalid"), env, List.of()).probe("probe-src");

        assertThat(h.healthStatus()).isEqualTo("GREEN");
    }

    @Test
    void without_an_override_a_registered_host_that_is_not_public_is_refused_not_called() {
        DataSourceHealth h = catalog(source("127.0.0.1:1"), new MockEnvironment(), List.of()).probe("probe-src");

        assertThat(h.healthStatus()).isEqualTo("RED");
        assertThat(h.detail()).contains("not a public address");
    }
}
