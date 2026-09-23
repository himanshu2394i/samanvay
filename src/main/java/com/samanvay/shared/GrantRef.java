package com.samanvay.shared;

import java.util.UUID;

public record GrantRef(UUID id, String category, String connectorRef) {}
