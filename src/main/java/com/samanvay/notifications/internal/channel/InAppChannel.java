package com.samanvay.notifications.internal.channel;

import com.samanvay.notifications.api.Channel;
import com.samanvay.notifications.api.DeliveryOutcome;
import com.samanvay.notifications.api.NotificationChannel;
import com.samanvay.notifications.api.RenderedMessage;
import org.springframework.stereotype.Component;

@Component
class InAppChannel implements NotificationChannel {
    @Override
    public Channel channel() {
        return Channel.IN_APP;
    }

    @Override
    public DeliveryOutcome send(RenderedMessage message) {
        return new DeliveryOutcome(true, null);
    }
}
