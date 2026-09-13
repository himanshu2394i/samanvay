package com.samanvay.notifications.api;

import java.util.UUID;

public record Subscription(UUID id, String recipientId, String eventType, Channel channel, String locale, boolean enabled) {}
