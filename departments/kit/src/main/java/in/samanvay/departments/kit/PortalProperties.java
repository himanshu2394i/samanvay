package in.samanvay.departments.kit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * One department's portal settings ({@code portal.*}). {@code samanvay.*} is how this department's server reaches Samanvay's
 * department API with the credential Samanvay issued it; none of it is ever sent to a browser.
 *
 * @param otpCode the one-time code a citizen enters at sign in and at consent (fixed for the demo; a real department sends a fresh code)
 * @param sessionSecret signs sign-in tickets and the session cookie; blank = random per process (a restart signs everyone out)
 * @param manifestKeyFile the file holding the department's manifest signing key; the same key signs consent statements, so Samanvay
 *     can check them against the key it pinned from the signed manifest
 */
@ConfigurationProperties("portal")
public record PortalProperties(
        String deptCode,
        String name,
        String initial,
        String accent,
        String accentDark,
        String onAccentDark,
        String publicBaseUrl,
        String otpCode,
        String sessionSecret,
        String manifestKeyFile,
        Samanvay samanvay) {

    public record Samanvay(String baseUrl, String tokenUrl, String clientId, String clientSecret) {}

    public PortalProperties {
        if (samanvay == null) {
            samanvay = new Samanvay(null, null, null, null);
        }
        if (manifestKeyFile == null || manifestKeyFile.isBlank()) {
            manifestKeyFile = "manifest-signing-key.jwk";
        }
        if (otpCode == null || otpCode.isBlank()) {
            otpCode = "123456";
        }
    }

    boolean secureCookies() {
        return publicBaseUrl != null && publicBaseUrl.toLowerCase().startsWith("https://");
    }
}
