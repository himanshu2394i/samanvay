package com.samanvay.notifications.internal.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.notifications.api.RenderedMessage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Docker-free guard for default-context safety: with the mail starter on the classpath but no {@code spring.mail.host}
 * (the default profile), Boot creates no JavaMailSender, the context still starts, and EMAIL degrades to a failed
 * outcome. With a host (dev profile) the sender exists.
 */
class EmailChannelContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withUserConfiguration(EmailChannel.class);

    @Test
    void contextStartsWithoutSmtpConfiguredAndEmailDegradesToFailure() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(JavaMailSender.class);
            var outcome = ctx.getBean(EmailChannel.class).send(new RenderedMessage("r", "b", "a@b.test", "s"));
            assertThat(outcome.sent()).isFalse();
        });
    }

    @Test
    void contextWithHostConfiguredCreatesTheMailSender() {
        runner.withPropertyValues("spring.mail.host=localhost", "spring.mail.port=1025").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(JavaMailSender.class);
        });
    }
}
