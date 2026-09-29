package com.samanvay.orchestration.internal.workflow;

import java.util.List;
import javax.sql.DataSource;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Programmatic Flowable wiring, active only when {@code samanvay.workflow.engine=flowable}.
 *
 * <p>The {@code flowable-spring-boot-starter} is deliberately not used: its autoconfiguration
 * would boot the engine (and create {@code ACT_*} tables) even in the default in-process mode.
 * Here the engine runs against the app's own {@link DataSource} and {@link PlatformTransactionManager}
 * (so Flowable work joins the caller's transaction). Flowable owns its {@code ACT_*} tables via
 * {@code databaseSchemaUpdate}; they are not part of Flyway.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = WorkflowEngineMode.PROPERTY, havingValue = WorkflowEngineMode.FLOWABLE)
class FlowableEngineConfiguration {

    @Bean(destroyMethod = "close")
    ProcessEngine processEngine(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            @Value("${samanvay.workflow.flowable.schema-update:true}") String schemaUpdate,
            @Value("${samanvay.workflow.flowable.async-executor:true}") boolean asyncExecutor) {
        SpringProcessEngineConfiguration cfg = new SpringProcessEngineConfiguration();
        cfg.setDataSource(dataSource);
        cfg.setTransactionManager(transactionManager);
        // "true": create/upgrade Flowable's ACT_* tables on boot. Set to "false" where the app's DB
        // role has no DDL rights and the tables are provisioned out of band.
        cfg.setDatabaseSchemaUpdate(schemaUpdate);
        // The async executor is what fires due timer jobs (incl. retries) and resumes them after a restart.
        cfg.setAsyncExecutorActivate(asyncExecutor);
        cfg.setCustomJobHandlers(List.of(new RetryJobHandler()));
        return cfg.buildProcessEngine();
    }

    @Bean
    RepositoryService repositoryService(ProcessEngine engine) {
        return engine.getRepositoryService();
    }

    @Bean
    RuntimeService runtimeService(ProcessEngine engine) {
        return engine.getRuntimeService();
    }

    @Bean
    ManagementService managementService(ProcessEngine engine) {
        return engine.getManagementService();
    }

    @Bean
    ProcessEngineConfigurationImpl flowableProcessEngineConfiguration(ProcessEngine engine) {
        return (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
    }
}
