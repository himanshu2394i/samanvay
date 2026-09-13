package com.samanvay.connector.api;

import tools.jackson.databind.JsonNode;

public record AdapterResponse(JsonNode body, int sizeBytes) {}
