package com.samanvay.notifications.internal.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.samanvay.notifications.api.Channel;
import com.samanvay.notifications.api.DeliveryOutcome;
import com.samanvay.notifications.api.RenderedMessage;
import jakarta.mail.internet.MimeMessage;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** EmailChannel against an in-JVM GreenMail SMTP server on a random port (no Docker). */
class EmailChannelTest {

    private GreenMail greenMail;

    @AfterEach
    void stop() {
        if (greenMail != null) {
            greenMail.stop();
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static JavaMailSenderImpl sender(int port) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(port);
        Properties p = sender.getJavaMailProperties();
        p.put("mail.smtp.connectiontimeout", "1000");
        p.put("mail.smtp.timeout", "2000");
        p.put("mail.smtp.writetimeout", "2000");
        return sender;
    }

    private static ObjectProvider<JavaMailSender> providerOf(JavaMailSender sender) {
        return new ObjectProvider<>() {
            @Override
            public JavaMailSender getObject() {
                return sender;
            }

            @Override
            public JavaMailSender getObject(Object... args) {
                return sender;
            }

            @Override
            public JavaMailSender getIfAvailable() {
                return sender;
            }

            @Override
            public JavaMailSender getIfUnique() {
                return sender;
            }
        };
    }

    @Test
    void reportsEmailChannel() {
        assertThat(new EmailChannel(providerOf(null), "a@b.c").channel()).isEqualTo(Channel.EMAIL);
    }

    @Test
    void deliversToSmtpServerWithRecipientSubjectAndBody() throws Exception {
        int port = freePort();
        greenMail = new GreenMail(new ServerSetup(port, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        greenMail.start();
        EmailChannel channel = new EmailChannel(providerOf(sender(port)), "no-reply@samanvay.test");

        DeliveryOutcome outcome = channel.send(
                new RenderedMessage("citizen-1", "Your consent was granted.", "asha@example.test", "Samanvay notification: ConsentGranted"));

        assertThat(outcome.sent()).isTrue();
        assertThat(outcome.error()).isNull();
        assertThat(greenMail.waitForIncomingEmail(2000, 1)).isTrue();
        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getAllRecipients()[0].toString()).isEqualTo("asha@example.test");
        assertThat(received[0].getFrom()[0].toString()).isEqualTo("no-reply@samanvay.test");
        assertThat(received[0].getSubject()).isEqualTo("Samanvay notification: ConsentGranted");
        assertThat(received[0].getContent().toString()).contains("Your consent was granted.");
    }

    @Test
    void unreachableSmtpReturnsFailedOutcomeQuicklyWithoutThrowing() throws Exception {
        int closedPort = freePort(); // nothing listens here any more
        EmailChannel channel = new EmailChannel(providerOf(sender(closedPort)), "no-reply@samanvay.test");

        long start = System.nanoTime();
        DeliveryOutcome outcome =
                channel.send(new RenderedMessage("citizen-1", "body", "asha@example.test", "subject"));

        assertThat(outcome.sent()).isFalse();
        assertThat(outcome.error()).isNotBlank();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void noMailSenderConfiguredReturnsFailedOutcome() {
        // The default context has no JavaMailSender bean (spring.mail.host unset).
        EmailChannel channel = new EmailChannel(providerOf(null), "no-reply@samanvay.test");

        DeliveryOutcome outcome = channel.send(new RenderedMessage("citizen-1", "body", "asha@example.test", "s"));

        assertThat(outcome.sent()).isFalse();
        assertThat(outcome.error()).contains("not configured");
    }

    @Test
    void missingAddressReturnsFailedOutcomeAndSendsNothing() throws Exception {
        greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());
        greenMail.start();
        EmailChannel channel =
                new EmailChannel(providerOf(sender(greenMail.getSmtp().getPort())), "no-reply@samanvay.test");

        DeliveryOutcome outcome = channel.send(new RenderedMessage("citizen-1", "body"));

        assertThat(outcome.sent()).isFalse();
        assertThat(outcome.error()).contains("address");
        assertThat(List.of(greenMail.getReceivedMessages())).isEmpty();
    }
}
