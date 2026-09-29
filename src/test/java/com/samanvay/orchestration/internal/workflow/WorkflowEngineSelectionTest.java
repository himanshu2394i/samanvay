package com.samanvay.orchestration.internal.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.orchestration.api.WorkflowEngine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Bean selection for the {@link WorkflowEngine} port: in-process is the default; Flowable is opt-in.
 * Exactly one engine bean must exist in every mode.
 */
class WorkflowEngineSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    InProcessWorkflowEngine.class, FlowableEngineConfiguration.class, FlowableWorkflowEngine.class);

    @Test
    void propertyUnsetYieldsInProcessEngineOnly() {
        // No DataSource / transaction manager is supplied: the Flowable wiring must be fully inert by default.
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(WorkflowEngine.class)).hasSize(1);
            assertThat(ctx).hasSingleBean(InProcessWorkflowEngine.class);
            assertThat(ctx).doesNotHaveBean(FlowableWorkflowEngine.class);
            assertThat(ctx).doesNotHaveBean(FlowableEngineConfiguration.class);
        });
    }

    @Test
    void explicitInProcessYieldsInProcessEngineOnly() {
        runner.withPropertyValues("samanvay.workflow.engine=in-process").run(ctx -> {
            assertThat(ctx.getBeansOfType(WorkflowEngine.class)).hasSize(1);
            assertThat(ctx).hasSingleBean(InProcessWorkflowEngine.class);
            assertThat(ctx).doesNotHaveBean(FlowableWorkflowEngine.class);
        });
    }

    @Test
    void flowablePropertyYieldsFlowableEngineOnly() {
        runner.withUserConfiguration(FlowableTestSupport.H2Config.class)
                .withPropertyValues("samanvay.workflow.engine=flowable", "samanvay.workflow.flowable.async-executor=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBeansOfType(WorkflowEngine.class)).hasSize(1);
                    assertThat(ctx).hasSingleBean(FlowableWorkflowEngine.class);
                    assertThat(ctx).doesNotHaveBean(InProcessWorkflowEngine.class);
                });
    }
}
