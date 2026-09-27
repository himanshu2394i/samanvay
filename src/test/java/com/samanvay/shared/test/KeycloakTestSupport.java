package com.samanvay.shared.test;

import static org.assertj.core.api.Assertions.assertThat;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * One pinned Keycloak per test JVM, started with the committed realm exports
 * (exactly what docker compose imports), plus small JDK-HttpClient helpers:
 * admin REST calls, token requests, and a scripted browser login (authorization
 * code + PKCE through Keycloak's real login forms).
 */
public final class KeycloakTestSupport {

    public static final String IMAGE = "quay.io/keycloak/keycloak:26.4";
    public static final String STAFF = "samanvay-staff";
    public static final String CITIZEN = "samanvay-citizen";
    public static final String UI_REDIRECT = "http://localhost:8080/";

    public static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern FORM_ACTION = Pattern.compile("<form[^>]*action=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private static KeycloakContainer keycloak;

    private KeycloakTestSupport() {}

    public static synchronized KeycloakContainer keycloak() {
        if (keycloak == null) {
            KeycloakContainer k = new KeycloakContainer(IMAGE)
                    .withRealmImportFiles(
                            "/keycloak-realms/samanvay-staff-realm.json", "/keycloak-realms/samanvay-citizen-realm.json");
            k.start();
            keycloak = k;
        }
        return keycloak;
    }

    public static String issuer(String realm) {
        return keycloak().getAuthServerUrl() + "/realms/" + realm;
    }

    public static String adminToken() throws Exception {
        return token("master", Map.of(
                        "grant_type", "password",
                        "client_id", "admin-cli",
                        "username", keycloak().getAdminUsername(),
                        "password", keycloak().getAdminPassword()))
                .get("access_token")
                .asString();
    }

    public static JsonNode admin(String path) throws Exception {
        HttpResponse<String> res = HTTP.send(
                HttpRequest.newBuilder(URI.create(keycloak().getAuthServerUrl() + path))
                        .header("Authorization", "Bearer " + adminToken())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as(path + " -> " + res.body()).isEqualTo(200);
        return JSON.readTree(res.body());
    }

    public static int adminPost(String path, String json) throws Exception {
        HttpResponse<String> res = HTTP.send(
                HttpRequest.newBuilder(URI.create(keycloak().getAuthServerUrl() + path))
                        .header("Authorization", "Bearer " + adminToken())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        return res.statusCode();
    }

    /** Token endpoint; asserts 200 and returns the JSON body. */
    public static JsonNode token(String realm, Map<String, String> form) throws Exception {
        HttpResponse<String> res = tokenResponse(realm, form);
        assertThat(res.statusCode()).as("token " + realm + " -> " + res.body()).isEqualTo(200);
        return JSON.readTree(res.body());
    }

    public static HttpResponse<String> tokenResponse(String realm, Map<String, String> form) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create(issuer(realm) + "/protocol/openid-connect/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form(form)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    public static JsonNode claims(String accessToken) {
        return JSON.readTree(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]));
    }

    /**
     * Imports a ready-to-use user (non-temporary password and, if {@code totpSecret}
     * is given, an enrolled TOTP credential) via partial import, so no required
     * actions stand between the test and a login.
     */
    public static void importUser(String realm, String username, String password, String totpSecret, String roles,
            Map<String, String> attributes) throws Exception {
        StringBuilder creds = new StringBuilder();
        if (password != null) {
            creds.append("{\"type\":\"password\",\"value\":\"").append(password).append("\",\"temporary\":false}");
        }
        if (totpSecret != null) {
            if (!creds.isEmpty()) {
                creds.append(',');
            }
            creds.append("{\"type\":\"otp\",\"userLabel\":\"test\",\"secretData\":\"{\\\"value\\\":\\\"")
                    .append(totpSecret)
                    .append("\\\"}\",\"credentialData\":\"{\\\"subType\\\":\\\"totp\\\",\\\"digits\\\":6,")
                    .append("\\\"counter\\\":0,\\\"period\\\":30,\\\"algorithm\\\":\\\"HmacSHA1\\\"}\"}");
        }
        String attrs = attributes.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":[\"" + e.getValue() + "\"]")
                .collect(Collectors.joining(","));
        String user = "{\"username\":\"" + username + "\",\"enabled\":true,\"emailVerified\":true,"
                + "\"email\":\"" + username + "@test.samanvay.invalid\",\"firstName\":\"T\",\"lastName\":\"" + username + "\","
                + "\"attributes\":{" + attrs + "},"
                + "\"realmRoles\":[" + roles + "],\"requiredActions\":[],\"credentials\":[" + creds + "]}";
        int status = adminPost("/admin/realms/" + realm + "/partialImport",
                "{\"ifResourceExists\":\"OVERWRITE\",\"users\":[" + user + "]}");
        assertThat(status).as("import " + username).isEqualTo(200);
    }

    /** RFC 6238 code for a Keycloak TOTP secret (Keycloak keys HMAC with the secret's raw bytes). */
    public static String totp(String secret) {
        try {
            long counter = System.currentTimeMillis() / 1000 / 30;
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] h = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(counter).array());
            int o = h[h.length - 1] & 0xf;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A browser session scripted over HTTP: cookies kept, redirects not followed. */
    public static final class BrowserLogin {
        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new PlainCookieJar())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        private final String realm;
        private final String clientId;
        private final String verifier;
        private HttpResponse<String> last;

        public BrowserLogin(String realm, String clientId) throws Exception {
            this.realm = realm;
            this.clientId = clientId;
            byte[] v = new byte[32];
            new SecureRandom().nextBytes(v);
            this.verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(v);
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            Map<String, String> q = new LinkedHashMap<>();
            q.put("client_id", clientId);
            q.put("response_type", "code");
            q.put("scope", "openid");
            q.put("redirect_uri", UI_REDIRECT);
            q.put("state", "st");
            q.put("code_challenge", challenge);
            q.put("code_challenge_method", "S256");
            last = client.send(
                    HttpRequest.newBuilder(URI.create(issuer(realm) + "/protocol/openid-connect/auth?" + form(q))).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(last.statusCode()).as("login page: " + last.body()).isEqualTo(200);
        }

        /** Submits the form on the current page. */
        public BrowserLogin submit(Map<String, String> fields) throws Exception {
            Matcher m = FORM_ACTION.matcher(last.body());
            assertThat(m.find()).as("a form on: " + last.body()).isTrue();
            String action = m.group(1).replace("&amp;", "&");
            last = client.send(
                    HttpRequest.newBuilder(URI.create(action))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form(fields)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return this;
        }

        public HttpResponse<String> page() {
            return last;
        }

        /** True once Keycloak redirected back to the UI with an authorization code. */
        public boolean finished() {
            return last.statusCode() == 302
                    && last.headers().firstValue("Location").orElse("").startsWith(UI_REDIRECT)
                    && last.headers().firstValue("Location").orElse("").contains("code=");
        }

        /** Exchanges the code for tokens; the login must have finished. */
        public String accessToken() throws Exception {
            assertThat(finished()).as("login finished; last page: " + last.statusCode() + " " + last.body()).isTrue();
            String location = last.headers().firstValue("Location").orElseThrow();
            String code = URI.create(location).getQuery().replaceAll(".*(?:^|&)code=([^&]+).*", "$1");
            return token(realm, Map.of(
                            "grant_type", "authorization_code",
                            "client_id", clientId,
                            "code", java.net.URLDecoder.decode(code, StandardCharsets.UTF_8),
                            "redirect_uri", UI_REDIRECT,
                            "code_verifier", verifier))
                    .get("access_token")
                    .asString();
        }
    }

    /**
     * Keeps every cookie and sends it back on plain http too. Keycloak marks its
     * login cookies Secure; browsers treat http://localhost as a secure context,
     * java.net.CookieManager does not.
     */
    static final class PlainCookieJar extends java.net.CookieHandler {
        private final Map<String, String> cookies = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public Map<String, java.util.List<String>> get(URI uri, Map<String, java.util.List<String>> headers) {
            if (cookies.isEmpty()) {
                return Map.of();
            }
            String header = cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("; "));
            return Map.of("Cookie", java.util.List.of(header));
        }

        @Override
        public void put(URI uri, Map<String, java.util.List<String>> headers) {
            headers.forEach((name, values) -> {
                if (name != null && name.equalsIgnoreCase("Set-Cookie")) {
                    for (String v : values) {
                        String pair = v.split(";", 2)[0];
                        int eq = pair.indexOf('=');
                        if (eq > 0) {
                            String key = pair.substring(0, eq).trim();
                            String value = pair.substring(eq + 1).trim();
                            if (value.isEmpty() || v.toLowerCase(java.util.Locale.ROOT).contains("max-age=0")) {
                                cookies.remove(key);
                            } else {
                                cookies.put(key, value);
                            }
                        }
                    }
                }
            });
        }
    }

    static String form(Map<String, String> form) {
        return form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
