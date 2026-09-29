package com.samanvay.orchestration.internal.workflow;

import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    @Configuration(proxyBeanMethods = false)
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
