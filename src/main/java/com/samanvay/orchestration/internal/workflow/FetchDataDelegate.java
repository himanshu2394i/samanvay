package com.samanvay.orchestration.internal.workflow;

import com.samanvay.consent.api.AccessAuthority;
import com.samanvay.consent.api.AccessDecision;
import com.samanvay.consent.api.AccessGrant;
import com.samanvay.consent.api.AccessRequest;
import com.samanvay.consent.api.ConsentUsage;
import com.samanvay.consent.api.DenialReason;
import com.samanvay.consent.api.InvalidGrantException;
import com.samanvay.connector.api.Capability;
import com.samanvay.connector.api.ConnectorResult;
import com.samanvay.connector.api.ConnectorRuntime;
import com.samanvay.connector.api.ExecutionInputs;
import com.samanvay.connector.api.FailureKind;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.core.JacksonException;

/**
 * Grant check, then the department call, then settling the consent's one-check claim.
 *
 * <p>Order matters for one-check consents: {@code authorize} claims the check and commits in its
 * own transaction, so the claim is visible to (and blocks) any concurrent check before the
 * department is called, and no database transaction is held open across the HTTP call. That is
 * why the fetch must not run inside a caller's transaction. After the call the claim is marked
 * USED on success and released (deleted, with an audit row) on any other outcome.
 */
@Component
public class FetchDataDelegate {

    private final AccessAuthority accessAuthority;
    private final ConnectorRuntime connectorRuntime;
    private final ConsentUsage usage;

    FetchDataDelegate(AccessAuthority accessAuthority, ConnectorRuntime connectorRuntime, ConsentUsage usage) {
        this.accessAuthority = accessAuthority;
        this.connectorRuntime = connectorRuntime;
        this.usage = usage;
    }

    public String execute(AccessRequest request, ExecutionInputs inputs) {
        requireNoTransaction();
        var decision = accessAuthority.authorize(request);
        if (decision instanceof AccessDecision.Denied denied) {
            if (denied.reason() == DenialReason.NO_CONSENT) {
                return "NO_CONSENT";
            }
            return denied.reason().name();
        }
        var result = callAndSettle(((AccessDecision.Granted) decision).grant(), inputs);
        return switch (result) {
            case ConnectorResult.Success s -> "COMPLETED";
            case ConnectorResult.Unavailable u when u.retryable() -> "PENDING_SOURCE";
            case ConnectorResult.NotFound nf -> "NOT_FOUND";
            case ConnectorResult.Invalid inv -> "INVALID";
            default -> "FAILED";
        };
    }

    public ConnectorResult executeForResult(AccessRequest request, ExecutionInputs inputs) {
        requireNoTransaction();
        var decision = accessAuthority.authorize(request);
        if (decision instanceof AccessDecision.Denied) {
            return new ConnectorResult.Unavailable(FailureKind.GRANT_INVALID, false);
        }
        return callAndSettle(((AccessDecision.Granted) decision).grant(), inputs);
    }

    private ConnectorResult callAndSettle(AccessGrant grant, ExecutionInputs inputs) {
        ConnectorResult result;
        try {
            result = connectorRuntime.execute(grant, Capability.FETCH, inputs);
        } catch (RuntimeException e) {
            usage.release(grant, failureReason(e));
            throw e;
        }
        if (result instanceof ConnectorResult.Success) {
            usage.markUsed(grant);
        } else {
            usage.release(grant, failureReason(result));
        }
        return result;
    }

    /**
     * Only a successful answer spends the check. NotFound, Invalid and Unavailable all leave the
     * citizen without the document, so the claim is released and a later check may run.
     */
    static String failureReason(ConnectorResult result) {
        return switch (result) {
            case ConnectorResult.Unavailable u -> u.kind().name();
            case ConnectorResult.Invalid inv -> "MALFORMED_RESPONSE";
            case ConnectorResult.NotFound nf -> "NOT_FOUND";
            case ConnectorResult.Success s -> throw new IllegalArgumentException("success is not a failure");
        };
    }

    static String failureReason(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException
                    || t instanceof java.util.concurrent.TimeoutException) {
                return FailureKind.TIMEOUT.name();
            }
            if (t instanceof HttpServerErrorException) {
                return FailureKind.REMOTE_FAULT.name();
            }
            if (t instanceof JacksonException) {
                return "MALFORMED_RESPONSE";
            }
            if (t instanceof InvalidGrantException) {
                return FailureKind.GRANT_INVALID.name();
            }
        }
        if (e instanceof ResourceAccessException) {
            return FailureKind.REMOTE_FAULT.name();
        }
        return "ERROR:" + e.getClass().getSimpleName();
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "fetch must run outside a transaction: the grant check commits its one-check claim"
                            + " before the department call, and no transaction may span that call");
        }
    }
}
