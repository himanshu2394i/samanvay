package com.samanvay.notifications.internal.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.notifications.api.Channel;
import com.samanvay.notifications.api.DeliveryOutcome;
import com.samanvay.notifications.api.RenderedMessage;
import org.junit.jupiter.api.Test;

class SmsChannelTest {

    @Test
    void stubReportsSmsChannelAndPlaceholderNotSentOutcome() {
        SmsChannel channel = new SmsChannel();

        DeliveryOutcome outcome = channel.send(new RenderedMessage("citizen-1", "body", "+911234567890", "s"));

        assertThat(channel.channel()).isEqualTo(Channel.SMS);
        assertThat(outcome.sent()).isFalse();
        assertThat(outcome.error()).isEqualTo(SmsChannel.STUB_ERROR).contains("stub");
    }
}
