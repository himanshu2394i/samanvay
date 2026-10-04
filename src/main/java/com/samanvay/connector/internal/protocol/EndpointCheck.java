package com.samanvay.connector.internal.protocol;

import com.samanvay.connector.api.IllegalConnectorConfigurationException;
import com.samanvay.shared.EndpointPath;
import java.net.URI;

/** Call-time guard for a department-supplied endpoint: it must be a plain path, and the finished URL must stay on the registered origin. */
final class EndpointCheck {

    private EndpointCheck() {}

    /** @return {@code endpoint} unchanged when it is a safe path (a query is allowed here), else refuses before any request is made */
    static String requireSafe(String source, String endpoint) {
        EndpointPath.problem(endpoint, true).ifPresent(why -> {
            throw new IllegalConnectorConfigurationException("source '" + source + "' has an endpoint that " + why);
        });
        return endpoint;
    }

    /** The URL as finally built must be on {@code origin} (scheme, host and port), with no userinfo. */
    static URI assertSameOrigin(URI target, String origin) {
        if (!EndpointPath.sameOrigin(target, origin)) {
            throw new IllegalConnectorConfigurationException("a department endpoint resolved to a host other than the registered one (" + URI.create(origin).getHost() + ")");
        }
        return target;
    }
}
