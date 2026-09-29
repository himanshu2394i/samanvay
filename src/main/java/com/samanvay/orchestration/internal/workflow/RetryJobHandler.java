package com.samanvay.orchestration.internal.workflow;

import org.flowable.common.engine.impl.interceptor.CommandContext;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.job.service.JobHandler;
import org.flowable.job.service.impl.persistence.entity.JobEntity;
import org.flowable.variable.api.delegate.VariableScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fires when a {@code scheduleRetry} timer job falls due: re-enters the activity if the instance is
 * still waiting at it (a fresh attempt). If the instance moved on or finished, the retry is a no-op.
 */
class RetryJobHandler implements JobHandler {

    static final String TYPE = "samanvay-retry";
    private static final Logger log = LoggerFactory.getLogger(RetryJobHandler.class);

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public void execute(JobEntity job, String activityId, VariableScope variableScope, CommandContext commandContext) {
        String instanceId = job.getProcessInstanceId();
        RuntimeService runtime = CommandContextUtil.getProcessEngineConfiguration(commandContext).getRuntimeService();
        boolean waiting =
                runtime.createExecutionQuery().processInstanceId(instanceId).activityId(activityId).count() > 0;
        if (!waiting) {
            log.info("Retry due for {}/{} but the instance is no longer at that activity; nothing to do", instanceId, activityId);
            return;
        }
        runtime.createChangeActivityStateBuilder()
                .processInstanceId(instanceId)
                .moveActivityIdTo(activityId, activityId)
                .changeState();
    }
}
