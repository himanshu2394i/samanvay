package com.samanvay.orchestration.internal.workflow;

import com.samanvay.orchestration.api.EngineState;
import com.samanvay.orchestration.api.WorkflowEngine;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.flowable.common.engine.api.scope.ScopeTypes;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.job.api.Job;
import org.flowable.job.service.JobServiceConfiguration;
import org.flowable.job.service.impl.persistence.entity.TimerJobEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Flowable-backed {@link WorkflowEngine}; opt-in via {@code samanvay.workflow.engine=flowable}
 * (see {@link FlowableEngineConfiguration}). State, timers and retries live in Flowable's {@code ACT_*}
 * tables, so they survive a restart.
 */
@Component
@ConditionalOnProperty(name = WorkflowEngineMode.PROPERTY, havingValue = WorkflowEngineMode.FLOWABLE)
class FlowableWorkflowEngine implements WorkflowEngine {

    private final RepositoryService repository;
    private final RuntimeService runtime;
    private final ManagementService management;
    private final ProcessEngineConfigurationImpl configuration;

    FlowableWorkflowEngine(
            RepositoryService repository,
            RuntimeService runtime,
            ManagementService management,
            ProcessEngineConfigurationImpl configuration) {
        this.repository = repository;
        this.runtime = runtime;
        this.management = management;
        this.configuration = configuration;
    }

    /** Deploys the BPMN; identical re-deploys are de-duplicated and return the existing deployment id. */
    @Override
    public String deploy(String definitionRef, byte[] definition) {
        return repository
                .createDeployment()
                .name(definitionRef)
                .addBytes(definitionRef + ".bpmn", definition)
                .enableDuplicateFiltering()
                .deploy()
                .getId();
    }

    @Override
    public String start(String processKey, Map<String, Object> variables) {
        return runtime.startProcessInstanceByKey(processKey, variables == null ? Map.of() : variables)
                .getId();
    }

    /**
     * Delivers {@code signal} to the instance, resolving in order: a waiting signal catch event of that
     * name, a waiting message catch event of that name, then an execution waiting at the activity whose
     * id equals {@code signal} (receive task / wait state). Variables are merged into the instance.
     *
     * @throws IllegalStateException if nothing in the instance is waiting for the signal
     */
    @Override
    public void signal(String instanceId, String signal, Map<String, Object> variables) {
        Map<String, Object> vars = variables == null ? Map.of() : variables;
        for (Execution e : runtime.createExecutionQuery()
                .processInstanceId(instanceId)
                .signalEventSubscriptionName(signal)
                .list()) {
            runtime.signalEventReceived(signal, e.getId(), vars);
            return;
        }
        for (Execution e : runtime.createExecutionQuery()
                .processInstanceId(instanceId)
                .messageEventSubscriptionName(signal)
                .list()) {
            runtime.messageEventReceived(signal, e.getId(), vars);
            return;
        }
        for (Execution e : runtime.createExecutionQuery()
                .processInstanceId(instanceId)
                .activityId(signal)
                .list()) {
            runtime.trigger(e.getId(), vars);
            return;
        }
        throw new IllegalStateException("Instance " + instanceId + " is not waiting for signal '" + signal + "'");
    }

    /**
     * Persists a timer job (ACT_RU_TIMER_JOB) that, once {@code delay} elapses, re-enters
     * {@code activityId} if the instance is still waiting there (see {@link RetryJobHandler}).
     * The job is bound to the instance's root execution, so it is removed when the instance ends.
     */
    @Override
    public void scheduleRetry(String instanceId, String activityId, Duration delay) {
        ProcessInstance instance = runtime.createProcessInstanceQuery()
                .processInstanceId(instanceId)
                .singleResult();
        if (instance == null) {
            throw new IllegalStateException("No running instance " + instanceId + " to schedule a retry for");
        }
        Date due = new Date(configuration.getClock().getCurrentTime().getTime() + delay.toMillis());
        management.executeCommand(commandContext -> {
            JobServiceConfiguration jobs = configuration.getJobServiceConfiguration();
            TimerJobEntity job = jobs.getTimerJobService().createTimerJob();
            job.setJobType(Job.JOB_TYPE_TIMER);
            job.setJobHandlerType(RetryJobHandler.TYPE);
            job.setJobHandlerConfiguration(activityId);
            job.setScopeType(ScopeTypes.BPMN);
            job.setProcessInstanceId(instance.getId());
            job.setExecutionId(instance.getId());
            job.setProcessDefinitionId(instance.getProcessDefinitionId());
            job.setElementId(activityId);
            job.setTenantId(instance.getTenantId());
            job.setExclusive(true);
            job.setRetries(jobs.getAsyncExecutorNumberOfRetries());
            job.setDuedate(due);
            jobs.getTimerJobService().scheduleTimerJob(job);
            return null;
        });
    }

    /** Runtime absence means finished (or unknown), matching the in-process engine's behavior. */
    @Override
    public EngineState state(String instanceId) {
        boolean running = runtime.createProcessInstanceQuery().processInstanceId(instanceId).count() > 0;
        if (!running) {
            return EngineState.completed();
        }
        List<String> active = runtime.getActiveActivityIds(instanceId);
        return EngineState.active(active);
    }
}
