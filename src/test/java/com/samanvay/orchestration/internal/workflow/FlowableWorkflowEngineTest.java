package com.samanvay.orchestration.internal.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.orchestration.api.EngineState;
import com.samanvay.orchestration.api.WorkflowEngine;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import javax.sql.DataSource;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RuntimeService;
import org.flowable.job.api.Job;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Drives the Flowable-backed engine on in-memory H2 (no Docker), with Flowable's own ACT_* auto-DDL.
 */
class FlowableWorkflowEngineTest {

    private static final String BPMN =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="https://samanvay.dev/bpmn">
              <signal id="approvedSignal" name="approved"/>
              <process id="miniJourney" isExecutable="true">
                <startEvent id="start"/>
                <receiveTask id="awaitDocs"/>
                <intermediateCatchEvent id="awaitApproval">
                  <signalEventDefinition signalRef="approvedSignal"/>
                </intermediateCatchEvent>
                <endEvent id="end"/>
                <sequenceFlow sourceRef="start" targetRef="awaitDocs"/>
                <sequenceFlow sourceRef="awaitDocs" targetRef="awaitApproval"/>
                <sequenceFlow sourceRef="awaitApproval" targetRef="end"/>
              </process>
            </definitions>
            """;

    private ApplicationContextRunner runner(String jdbcUrl) {
        return new ApplicationContextRunner()
                .withUserConfiguration(FlowableEngineConfiguration.class, FlowableWorkflowEngine.class, Db.class)
                .withPropertyValues(
                        "samanvay.workflow.engine=flowable",
                        // no background executor: the test fires due jobs itself, deterministically
                        "samanvay.workflow.flowable.async-executor=false",
                        "test.jdbc-url=" + jdbcUrl);
    }

    @Test
    void startSignalAndStateDriveAnInstanceToCompletion() {
        runner(FlowableTestSupport.newJdbcUrl()).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            WorkflowEngine engine = ctx.getBean(WorkflowEngine.class);
            RuntimeService runtime = ctx.getBean(RuntimeService.class);

            assertThat(engine.deploy("mini", BPMN.getBytes(StandardCharsets.UTF_8))).isNotBlank();

            String id = engine.start("miniJourney", Map.of("citizenId", "c-1"));
            assertThat(id).isNotBlank();
            assertThat(engine.state(id)).isEqualTo(EngineState.active(java.util.List.of("awaitDocs")));

            // receive-task wait state: signalled by activity id; variables are merged
            engine.signal(id, "awaitDocs", Map.of("docs", "received"));
            assertThat(runtime.getVariable(id, "docs")).isEqualTo("received");
            assertThat(runtime.getVariable(id, "citizenId")).isEqualTo("c-1");
            assertThat(engine.state(id).activeActivities()).containsExactly("awaitApproval");

            // signal catch event: signalled by signal name
            engine.signal(id, "approved", Map.of());
            assertThat(engine.state(id)).isEqualTo(EngineState.completed());
            assertThat(engine.state(id).done()).isTrue();
        });
    }

    @Test
    void signalWithNothingWaitingIsRejectedNotSilentlyDropped() {
        runner(FlowableTestSupport.newJdbcUrl()).run(ctx -> {
            WorkflowEngine engine = ctx.getBean(WorkflowEngine.class);
            engine.deploy("mini", BPMN.getBytes(StandardCharsets.UTF_8));
            String id = engine.start("miniJourney", Map.of());
            assertThatThrownBy(() -> engine.signal(id, "nope", Map.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("nope");
        });
    }

    @Test
    void redeployingIdenticalDefinitionIsIdempotent() {
        runner(FlowableTestSupport.newJdbcUrl()).run(ctx -> {
            WorkflowEngine engine = ctx.getBean(WorkflowEngine.class);
            byte[] bpmn = BPMN.getBytes(StandardCharsets.UTF_8);
            assertThat(engine.deploy("mini", bpmn)).isEqualTo(engine.deploy("mini", bpmn));
        });
    }

    @Test
    void scheduleRetryPersistsATimerJobThatReentersTheActivityWhenDue() {
        runner(FlowableTestSupport.newJdbcUrl()).run(ctx -> {
            WorkflowEngine engine = ctx.getBean(WorkflowEngine.class);
            ManagementService management = ctx.getBean(ManagementService.class);
            HistoryService history = ctx.getBean(org.flowable.engine.ProcessEngine.class).getHistoryService();

            engine.deploy("mini", BPMN.getBytes(StandardCharsets.UTF_8));
            String id = engine.start("miniJourney", Map.of());
            assertThat(management.createTimerJobQuery().processInstanceId(id).count()).isZero();

            engine.scheduleRetry(id, "awaitDocs", Duration.ofMinutes(5));

            Job timer = management.createTimerJobQuery().processInstanceId(id).singleResult();
            assertThat(timer).isNotNull();
            assertThat(timer.getJobHandlerType()).isEqualTo(RetryJobHandler.TYPE);
            assertThat(timer.getJobHandlerConfiguration()).isEqualTo("awaitDocs");
            assertThat(timer.getDuedate()).isAfter(new java.util.Date(System.currentTimeMillis() + Duration.ofMinutes(4).toMillis()));
            assertThat(history.createHistoricActivityInstanceQuery().processInstanceId(id).activityId("awaitDocs").count())
                    .isEqualTo(1);

            // due: promote to an executable job and run it (what the async executor does in production)
            management.moveTimerToExecutableJob(timer.getId());
            management.executeJob(timer.getId());

            assertThat(management.createTimerJobQuery().processInstanceId(id).count()).isZero();
            assertThat(management.createJobQuery().processInstanceId(id).count()).isZero();
            assertThat(history.createHistoricActivityInstanceQuery().processInstanceId(id).activityId("awaitDocs").count())
                    .as("activity re-entered by the retry")
                    .isEqualTo(2);
            assertThat(engine.state(id).activeActivities()).containsExactly("awaitDocs");
        });
    }

    @Test
    void scheduleRetryOnUnknownInstanceFails() {
        runner(FlowableTestSupport.newJdbcUrl()).run(ctx -> {
            WorkflowEngine engine = ctx.getBean(WorkflowEngine.class);
            assertThatThrownBy(() -> engine.scheduleRetry("missing", "awaitDocs", Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void timerJobsAndInstancesSurviveARestart() {
        String url = FlowableTestSupport.newJdbcUrl();
        String[] ids = new String[1];

        // "first boot": deploy, start, schedule a retry, then shut the whole engine down
        runner(url).run(ctx -> {
            WorkflowEngine engine = ctx.getBean(WorkflowEngine.class);
            engine.deploy("mini", BPMN.getBytes(StandardCharsets.UTF_8));
            ids[0] = engine.start("miniJourney", Map.of("citizenId", "c-9"));
            engine.scheduleRetry(ids[0], "awaitDocs", Duration.ofHours(1));
        });

        // "second boot": a brand-new engine on the same database still has the instance and its timer
        runner(url).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            WorkflowEngine engine = ctx.getBean(WorkflowEngine.class);
            ManagementService management = ctx.getBean(ManagementService.class);
            assertThat(engine.state(ids[0]).done()).isFalse();
            assertThat(engine.state(ids[0]).activeActivities()).containsExactly("awaitDocs");
            assertThat(management.createTimerJobQuery().processInstanceId(ids[0]).count()).isEqualTo(1);
            assertThat(ctx.getBean(RuntimeService.class).getVariable(ids[0], "citizenId")).isEqualTo("c-9");
        });
    }

    @Test
    void retryTimerIsRemovedWhenTheInstanceCompletes() {
        runner(FlowableTestSupport.newJdbcUrl()).run(ctx -> {
            WorkflowEngine engine = ctx.getBean(WorkflowEngine.class);
            ManagementService management = ctx.getBean(ManagementService.class);
            engine.deploy("mini", BPMN.getBytes(StandardCharsets.UTF_8));
            String id = engine.start("miniJourney", Map.of());
            engine.scheduleRetry(id, "awaitDocs", Duration.ofHours(1));
            engine.signal(id, "awaitDocs", Map.of());
            engine.signal(id, "approved", Map.of());
            assertThat(engine.state(id).done()).isTrue();
            assertThat(management.createTimerJobQuery().processInstanceId(id).count()).isZero();
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Db {
        @Bean
        DataSource dataSource(org.springframework.core.env.Environment env) {
            var ds = new DriverManagerDataSource(env.getRequiredProperty("test.jdbc-url"), "sa", "");
            ds.setDriverClassName("org.h2.Driver");
            return ds;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
