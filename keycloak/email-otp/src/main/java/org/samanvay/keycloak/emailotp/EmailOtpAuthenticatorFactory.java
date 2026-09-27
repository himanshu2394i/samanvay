package org.samanvay.keycloak.emailotp;

import java.util.List;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

/** Registers {@link EmailOtpAuthenticator} as {@value #ID}. */
public final class EmailOtpAuthenticatorFactory implements AuthenticatorFactory {

    public static final String ID = "samanvay-email-otp";

    private static final EmailOtpAuthenticator SINGLETON = new EmailOtpAuthenticator();

    private static final AuthenticationExecutionModel.Requirement[] CHOICES = {
        AuthenticationExecutionModel.Requirement.REQUIRED,
        AuthenticationExecutionModel.Requirement.ALTERNATIVE,
        AuthenticationExecutionModel.Requirement.DISABLED
    };

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayType() {
        return "Email one-time code (Samanvay)";
    }

    @Override
    public String getHelpText() {
        return "Emails a one-time code to the user's address and accepts it once.";
    }

    @Override
    public String getReferenceCategory() {
        return "email-otp";
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return CHOICES;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of(
                property("length", "Code length", "Digits in the code", EmailOtpAuthenticator.DEFAULT_LENGTH),
                property("ttlSeconds", "Lifetime (seconds)", "How long a code stays valid",
                        EmailOtpAuthenticator.DEFAULT_TTL_SECONDS),
                property("maxAttempts", "Maximum attempts", "Wrong entries before the code is discarded",
                        EmailOtpAuthenticator.DEFAULT_MAX_ATTEMPTS));
    }

    private static ProviderConfigProperty property(String name, String label, String help, int defaultValue) {
        ProviderConfigProperty p = new ProviderConfigProperty();
        p.setName(name);
        p.setLabel(label);
        p.setHelpText(help);
        p.setType(ProviderConfigProperty.STRING_TYPE);
        p.setDefaultValue(Integer.toString(defaultValue));
        return p;
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return SINGLETON;
    }

    @Override
    public void init(Config.Scope config) {}

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {}
}
