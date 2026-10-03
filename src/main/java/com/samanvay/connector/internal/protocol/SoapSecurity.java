package com.samanvay.connector.internal.protocol;

import com.samanvay.connector.api.IllegalConnectorConfigurationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds a WS-Security {@code UsernameToken} (PasswordText) to the SOAP header of an already-rendered envelope.
 * The token is built from escaped values, so a credential cannot break out of its element.
 *
 * <p>ponytail: PasswordText only (assumes TLS to the department); PasswordDigest with a nonce and timestamp when a
 * real department requires it. The envelope is edited as text (the template is the department's own, rendered with
 * escaped inputs); a structural XML edit when templates get more exotic than prefix:Envelope / Header / Body.
 */
final class SoapSecurity {

    static final String WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
    static final String PASSWORD_TEXT =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText";

    private static final Pattern ENVELOPE = Pattern.compile("<(?:([A-Za-z_][\\w.-]*):)?Envelope\\b");

    private SoapSecurity() {}

    static String addUsernameToken(String envelope, String username, String password) {
        Matcher env = ENVELOPE.matcher(envelope);
        if (!env.find()) {
            throw new IllegalConnectorConfigurationException(
                    "a WS-Security header needs the request template to be a full SOAP envelope");
        }
        String prefix = env.group(1) == null ? "" : env.group(1) + ":";
        String security = "<wsse:Security xmlns:wsse=\"" + WSSE + "\"><wsse:UsernameToken><wsse:Username>"
                + SoapAdapter.escapeXml(username) + "</wsse:Username><wsse:Password Type=\"" + PASSWORD_TEXT + "\">"
                + SoapAdapter.escapeXml(password) + "</wsse:Password></wsse:UsernameToken></wsse:Security>";

        Matcher empty = Pattern.compile("<" + prefix + "Header\\s*/>").matcher(envelope);
        if (empty.find()) {
            return envelope.substring(0, empty.start()) + "<" + prefix + "Header>" + security + "</" + prefix + "Header>"
                    + envelope.substring(empty.end());
        }
        Matcher open = Pattern.compile("<" + prefix + "Header(?:\\s[^>]*)?>").matcher(envelope);
        if (open.find()) {
            return envelope.substring(0, open.end()) + security + envelope.substring(open.end());
        }
        Matcher body = Pattern.compile("<" + prefix + "Body\\b").matcher(envelope);
        if (body.find()) {
            return envelope.substring(0, body.start()) + "<" + prefix + "Header>" + security + "</" + prefix + "Header>"
                    + envelope.substring(body.start());
        }
        throw new IllegalConnectorConfigurationException("the SOAP request template has no Body to put a security header before");
    }
}
