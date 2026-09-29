package com.samanvay.orchestration.internal.workflow;

import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** Docker-free H2 fixtures for the Flowable engine tests. */
final class FlowableTestSupport {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private FlowableTestSupport() {}

    /** A fresh, named in-memory database that outlives a Spring context (to simulate a restart). */
    static String newJdbcUrl() {
        return "jdbc:h2:mem:flowable-" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
    }

    /**
     * Must be a {@code @TestConfiguration}, never a plain {@code @Configuration}: SamanvayApplication's
     * component scan covers the test classpath, and a scanned plain {@code @Configuration} that defines
     * a {@code DataSource} would replace the app's real one in every full-context IT.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class H2Config {

        @Bean
        DataSource dataSource() {
            var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(newJdbcUrl(), "sa", "");
            ds.setDriverClassName("org.h2.Driver");
            return ds;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
