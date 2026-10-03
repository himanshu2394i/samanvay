package org.samanvay.keycloak.fixedotp;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.browser.OTPFormAuthenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/**
 * Keycloak's own one-time-code step, with ONE addition for demo servers: when {@value FixedOtp#ENV} is set to six digits, that
 * code is also accepted, and a user with no authenticator app does not have to enrol one first.
 *
 * <p>With the variable unset (the default, and always in production) this behaves exactly like the stock {@code auth-otp-form}:
 * only a real authenticator-app code works and enrolment is forced. The fixed code is a deliberate back door, so it is off unless
 * an operator turns it on, and the server says so in its log when it starts.
 */
public final class FixedOtpFormAuthenticator extends OTPFormAuthenticator {

    private final String fixed;

    FixedOtpFormAuthenticator(String fixed) {
        this.fixed = fixed;
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        String submitted = context.getHttpRequest().getDecodedFormParameters().getFirst("otp");
        if (FixedOtp.matches(fixed, submitted) && context.getUser() != null) {
            context.success();
            return;
        }
        super.action(context);
    }

    /** With the demo code on, nobody has to enrol an authenticator app first. */
    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return fixed != null || super.configuredFor(session, realm, user);
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        if (fixed == null) {
            super.setRequiredActions(session, realm, user);
        }
    }
}
