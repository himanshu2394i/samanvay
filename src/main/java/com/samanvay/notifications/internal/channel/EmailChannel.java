package com.samanvay.notifications.internal.channel;

import com.samanvay.notifications.api.Channel;
import com.samanvay.notifications.api.DeliveryOutcome;
import com.samanvay.notifications.api.NotificationChannel;
import com.samanvay.notifications.api.RenderedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * EMAIL delivery through Spring's {@link JavaMailSender} (Mailpit in dev, see {@code application-dev.yml}).
 *
 * <p>The recipient address is the one stored on the recipient's EMAIL subscription and passed in
 * {@link RenderedMessage#address()}; notifications keeps no dependency on identity for contact details.
 *
 * <p>Default-context safety: Boot only creates a {@code JavaMailSender} when {@code spring.mail.host} is set, so
 * the sender is injected lazily through an {@link ObjectProvider}. With no SMTP configured the application still
 * boots and {@link #send} returns a failed {@link DeliveryOutcome} instead of throwing. Connect/read/write
 * timeouts (application.yml) keep an unreachable server from hanging the dispatcher.
 */
@Component
public class EmailChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);
    private static final int MAX_ERROR = 300;

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String from;

    public EmailChannel(
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${samanvay.notifications.email.from:no-reply@samanvay.local}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    @Override
    public DeliveryOutcome send(RenderedMessage message) {
        String to = message.address();
        if (to == null || to.isBlank()) {
            return fail("no email address on subscription", message);
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            return fail("email not configured (spring.mail.host is not set)", message);
        }
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom(from);
            mail.setTo(to.trim());
            mail.setSubject(message.subject() == null || message.subject().isBlank() ? "Samanvay notification" : message.subject());
            mail.setText(message.body() == null ? "" : message.body());
            sender.send(mail);
            return new DeliveryOutcome(true, null);
        } catch (RuntimeException ex) {
            // MailException (unreachable/refused/auth) and anything else: report, never throw out of send().
            return fail(ex.getClass().getSimpleName() + ": " + ex.getMessage(), message);
        }
    }

    private DeliveryOutcome fail(String reason, RenderedMessage message) {
        String error = reason.length() > MAX_ERROR ? reason.substring(0, MAX_ERROR) : reason;
        // recipientId only: the address is personal data and stays out of the logs.
        log.warn("email delivery failed for recipient {}: {}", message.recipientId(), error);
        return new DeliveryOutcome(false, error);
    }
}
