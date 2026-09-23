package com.samanvay.notifications.api;

public interface NotificationChannel {
    Channel channel();

    DeliveryOutcome send(RenderedMessage message);
}
