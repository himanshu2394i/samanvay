package com.samanvay.notifications.internal.channel;

import com.samanvay.notifications.api.Channel;
import com.samanvay.notifications.api.DeliveryOutcome;
import com.samanvay.notifications.api.NotificationChannel;
import com.samanvay.notifications.api.RenderedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SMS channel, <strong>STUB</strong>. Nothing is sent: it logs and returns a not-sent {@link DeliveryOutcome}, so an
 * SMS subscription is recorded as a FAILED delivery ("stub") rather than being reported as SENT or breaking the
 * dispatcher.
 *
 * <p>TODO (follow-up): integrate a real SMS provider/gateway behind this channel (credentials, DLT template
 * registration, delivery receipts). No provider is integrated on purpose.
 */
@Component
class SmsChannel implements NotificationChannel {

    static final String STUB_ERROR = "SMS delivery not implemented (stub channel, no provider integrated)";

    private static final Logger log = LoggerFactory.getLogger(SmsChannel.class);

    @Override
    public Channel channel() {
        return Channel.SMS;
    }

    @Override
    public DeliveryOutcome send(RenderedMessage message) {
        log.info("SMS stub: not delivering notification to recipient {}", message.recipientId());
        return new DeliveryOutcome(false, STUB_ERROR);
    }
}
