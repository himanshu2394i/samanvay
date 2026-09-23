package com.samanvay.orchestration.internal.workflow;

import com.samanvay.consent.api.AccessAuthority;
import com.samanvay.consent.api.AccessDecision;
import com.samanvay.consent.api.AccessRequest;
import com.samanvay.consent.api.DenialReason;
import com.samanvay.connector.api.Capability;
import com.samanvay.connector.api.ConnectorResult;
import com.samanvay.connector.api.ConnectorRuntime;
import com.samanvay.connector.api.ExecutionInputs;
import org.springframework.stereotype.Component;

@Component
public class FetchDataDelegate {

    private final AccessAuthority accessAuthority;
    private final ConnectorRuntime connectorRuntime;

    FetchDataDelegate(AccessAuthority accessAuthority, ConnectorRuntime connectorRuntime) {
        this.accessAuthority = accessAuthority;
        this.connectorRuntime = connectorRuntime;
    }

    public String execute(AccessRequest request, ExecutionInputs inputs) {
        var decision = accessAuthority.authorize(request);
        if (decision instanceof AccessDecision.Denied denied) {
            if (denied.reason() == DenialReason.NO_CONSENT) {
                return "NO_CONSENT";
            }
            return denied.reason().name();
        }
        var grant = ((AccessDecision.Granted) decision).grant();
        var result = connectorRuntime.execute(grant, Capability.FETCH, inputs);
        return switch (result) {
            case ConnectorResult.Success s -> "COMPLETED";
            case ConnectorResult.Unavailable u when u.retryable() -> "PENDING_SOURCE";
            case ConnectorResult.NotFound nf -> "NOT_FOUND";
            case ConnectorResult.Invalid inv -> "INVALID";
            default -> "FAILED";
        };
    }

    public ConnectorResult executeForResult(AccessRequest request, ExecutionInputs inputs) {
        var decision = accessAuthority.authorize(request);
        if (decision instanceof AccessDecision.Denied) {
            return new ConnectorResult.Unavailable(com.samanvay.connector.api.FailureKind.GRANT_INVALID, false);
        }
        var grant = ((AccessDecision.Granted) decision).grant();
        return connectorRuntime.execute(grant, Capability.FETCH, inputs);
    }
}
