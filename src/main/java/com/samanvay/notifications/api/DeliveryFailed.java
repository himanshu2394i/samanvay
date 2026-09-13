package com.samanvay.notifications.api;

import java.util.UUID;

public record DeliveryFailed(UUID deliveryId, Channel channel, String error) {}
