package com.samanvay.orchestration.internal.workflow;

/** Property that selects which {@code WorkflowEngine} bean the orchestration module wires. */
final class WorkflowEngineMode {

    static final String PROPERTY = "samanvay.workflow.engine";
    /** Default (property unset): {@code InProcessWorkflowEngine}. */
    static final String IN_PROCESS = "in-process";
    /** Opt-in: {@code FlowableWorkflowEngine} on Flowable's own ACT_* tables. */
    static final String FLOWABLE = "flowable";

    private WorkflowEngineMode() {}
}
