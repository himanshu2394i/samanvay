package com.samanvay.identity.internal.proof;

import static org.assertj.core.api.Assertions.assertThat;

import com.samanvay.identity.api.LinkProofProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** The fixed-OTP proof is a demo stand-in: absent unless samanvay.identity.demo-otp-link=true. */
class LocalIdOtpLinkProofProviderGatingTest {

    @Configuration
    @Import(LocalIdOtpLinkProofProvider.class)
    static class Config {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void absentByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(LinkProofProvider.class));
    }

    @Test
    void presentWhenSwitchedOn() {
        runner.withPropertyValues("samanvay.identity.demo-otp-link=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(LinkProofProvider.class));
    }
}
