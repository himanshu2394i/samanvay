package com.samanvay.connector.api;

import java.util.List;
import tools.jackson.databind.JsonNode;

public sealed interface ConnectorResult {
    record Success(JsonNode canonical, Provenance provenance) implements ConnectorResult {}

    record NotFound(String detail) implements ConnectorResult {}

    record Unavailable(FailureKind kind, boolean retryable) implements ConnectorResult {}

    record Invalid(List<String> violations) implements ConnectorResult {}
}
