package com.samanvay.notifications.api;

import java.util.UUID;

public record DeliveryRecord(UUID id, String recipientId, String eventType, Channel channel, String status, String body) {}
