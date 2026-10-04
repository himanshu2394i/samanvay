package in.samanvay.departments.kit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * One department's portal settings ({@code portal.*}). {@code samanvay.*} is how this department's server reaches Samanvay's
 * department API with the credential Samanvay issued it; none of it is ever sent to a browser.
 *
 * @param otpCode the one-time code a citizen enters at sign in and at consent (fixed: a real department sends a fresh code). The default
 *     {@code 123456} is only accepted in demo mode ({@code department.demo-mode=true}, see {@link StartupChecks})
 * @param sessionSecret signs sign-in tickets and the session cookie; blank = one made on first start and kept in a file. A configured
 *     one must be at least 32 bytes (outside demo mode)
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

    static final String DEFAULT_OTP = "123456";

    public record Samanvay(String baseUrl, String tokenUrl, String clientId, String clientSecret) {
        @Override
        public String toString() {
            return "Samanvay[baseUrl=" + baseUrl + ", tokenUrl=" + tokenUrl + ", clientId=" + clientId + ", clientSecret=***]";
        }
    }

    public PortalProperties {
        if (samanvay == null) {
            samanvay = new Samanvay(null, null, null, null);
        }
        if (manifestKeyFile == null || manifestKeyFile.isBlank()) {
            manifestKeyFile = "manifest-signing-key.jwk";
        }
        if (otpCode == null || otpCode.isBlank()) {
            otpCode = DEFAULT_OTP;
        }
    }

    boolean secureCookies() {
        return publicBaseUrl != null && publicBaseUrl.toLowerCase().startsWith("https://");
    }

    /** Never prints the one-time code, the session secret or the client secret (it ends up in logs and error pages). */
    @Override
    public String toString() {
        return "PortalProperties[deptCode=" + deptCode + ", name=" + name + ", publicBaseUrl=" + publicBaseUrl + ", manifestKeyFile=" + manifestKeyFile
                + ", otpCode=***, sessionSecret=***, samanvay=" + samanvay + "]";
    }
}
