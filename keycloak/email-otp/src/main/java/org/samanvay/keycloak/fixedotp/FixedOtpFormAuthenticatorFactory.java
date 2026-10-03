package org.samanvay.keycloak.fixedotp;

import java.util.List;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

/** Registers {@link FixedOtpFormAuthenticator} as {@value #ID}: the staff second factor, with the optional demo fixed code. */
public final class FixedOtpFormAuthenticatorFactory implements AuthenticatorFactory {

    public static final String ID = "samanvay-otp-form";
    private static final Logger LOG = Logger.getLogger(FixedOtpFormAuthenticatorFactory.class);

    private static final AuthenticationExecutionModel.Requirement[] CHOICES = {
        AuthenticationExecutionModel.Requirement.REQUIRED, AuthenticationExecutionModel.Requirement.DISABLED
    };

    private Authenticator authenticator;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayType() {
        return "OTP form (Samanvay, optional demo fixed code)";
    }

    @Override
    public String getHelpText() {
        return "Validates the authenticator-app code like the stock OTP form. Only if SAMANVAY_DEMO_FIXED_OTP is set (demo "
                + "servers) is that fixed six-digit code also accepted.";
    }

    @Override
    public String getReferenceCategory() {
        return "otp";
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return CHOICES;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return true;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of();
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return authenticator;
    }

    @Override
    public void init(Config.Scope config) {
        String fixed = FixedOtp.fromEnvironment();
        String raw = System.getenv(FixedOtp.ENV);
        if (fixed != null) {
            LOG.warn("DEMO ONLY: " + FixedOtp.ENV + " is set, so the staff second factor ALSO accepts a fixed code. "
                    + "Never run production like this.");
        } else if (raw != null && !raw.isBlank()) {
            LOG.error(FixedOtp.ENV + " is set but is not exactly six digits, so it is IGNORED (fixed code stays off).");
        }
        authenticator = new FixedOtpFormAuthenticator(fixed);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {}
}
