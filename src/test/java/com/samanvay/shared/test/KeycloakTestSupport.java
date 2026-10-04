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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
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

    public static final String IMAGE = "quay.io/keycloak/keycloak:26.4.7";
    public static final String STAFF = "samanvay-staff";
    public static final String CITIZEN = "samanvay-citizen";
    /** The MOCK department identity provider the citizen realm brokers to. */
    public static final String DEPARTMENT = "samanvay-department";
    /** The citizen realm's identity-provider alias for it (also the kc_idp_hint value). */
    public static final String BROKER_ALIAS = "dept-idp";
    public static final String UI_REDIRECT = "http://localhost:8080/";

    public static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern FORM_ACTION = Pattern.compile("<form[^>]*action=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    /** Built by the Maven build (process-test-classes) from keycloak/email-otp. */
    public static final java.io.File EMAIL_OTP_PROVIDER =
            new java.io.File("target/keycloak-providers/samanvay-keycloak-email-otp.jar");
    public static final String MAILPIT_IMAGE = "axllent/mailpit:v1.27";

    private static KeycloakContainer keycloak;
    private static GenericContainer<?> mailpit;

    private KeycloakTestSupport() {}

    /**
     * Keycloak plus the Mailpit mail catcher, reachable from Keycloak under the
     * host name {@code mailpit} - the SMTP host the committed citizen realm
     * points at, as in docker compose. Both stay on Docker's default bridge (a
     * host entry, not a user-defined network: some sandboxed Docker hosts drop
     * container-to-container traffic on user-defined networks).
     */
    public static synchronized KeycloakContainer keycloak() {
        if (keycloak == null) {
            assertThat(EMAIL_OTP_PROVIDER).as("email OTP provider jar (run the Maven build)").exists();
            GenericContainer<?> mail = new GenericContainer<>(MAILPIT_IMAGE)
                    .withExposedPorts(8025, 1025)
                    .waitingFor(Wait.forHttp("/livez").forPort(8025));
            mail.start();
            mailpit = mail;
            // Newer Docker (e.g. Docker Desktop) leaves the top-level address empty and reports it per network.
            var net = mail.getContainerInfo().getNetworkSettings();
            String mailpitIp = net.getIpAddress() != null && !net.getIpAddress().isBlank()
                    ? net.getIpAddress()
                    : net.getNetworks().values().iterator().next().getIpAddress();
            KeycloakContainer k = new KeycloakContainer(IMAGE)
                    .withExtraHost("mailpit", mailpitIp)
                    .withProviderLibsFrom(List.of(EMAIL_OTP_PROVIDER))
                    .withRealmImportFiles(
                            "/keycloak-realms/samanvay-staff-realm.json",
                            "/keycloak-realms/samanvay-citizen-realm.json",
                            "/keycloak-realms/samanvay-department-realm.json");
            k.start();
            keycloak = k;
            try {
                repointBrokerAtThisContainer();
            } catch (Exception e) {
                throw new IllegalStateException("cannot point the citizen realm's broker at this container", e);
            }
        }
        return keycloak;
    }

    /**
     * The committed realms address Keycloak as docker compose does (http://localhost:8180, one URL for
     * browser and server). In the test container the browser-facing address is a mapped port and Keycloak
     * itself listens on 8080, so the broker's URLs (and the department client's redirect URI) are rewritten
     * once after import through the admin API. Everything else - mappers, flows, the client, the secret,
     * PKCE, signature validation - stays exactly as committed. The upstream {@code issuer} is dropped only
     * because the token endpoint is now called on the container-internal host name, which Keycloak then
     * (correctly) puts in {@code iss}.
     */
    private static void repointBrokerAtThisContainer() throws Exception {
        String browser = keycloak.getAuthServerUrl();
        String internal = "http://localhost:8080";
        String oidc = "/realms/" + DEPARTMENT + "/protocol/openid-connect";

        String idpPath = "/admin/realms/" + CITIZEN + "/identity-provider/instances/" + BROKER_ALIAS;
        tools.jackson.databind.node.ObjectNode idp = (tools.jackson.databind.node.ObjectNode) admin(idpPath);
        tools.jackson.databind.node.ObjectNode config = (tools.jackson.databind.node.ObjectNode) idp.get("config");
        config.put("authorizationUrl", browser + oidc + "/auth");
        config.put("tokenUrl", internal + oidc + "/token");
        config.put("jwksUrl", internal + oidc + "/certs");
        config.put("logoutUrl", browser + oidc + "/logout");
        config.remove("issuer");
        assertThat(adminPut(idpPath, idp.toString())).as("update broker").isBetween(200, 204);

        JsonNode found = admin("/admin/realms/" + DEPARTMENT + "/clients?clientId=samanvay-citizen-broker").get(0);
        tools.jackson.databind.node.ObjectNode client = (tools.jackson.databind.node.ObjectNode) found;
        client.set("redirectUris", JSON.createArrayNode()
                .add(browser + "/realms/" + CITIZEN + "/broker/" + BROKER_ALIAS + "/endpoint"));
        assertThat(adminPut("/admin/realms/" + DEPARTMENT + "/clients/" + found.get("id").asString(), client.toString()))
                .as("update department client")
                .isBetween(200, 204);
    }

    /**
     * A citizen signs in through the mock department IdP (password + TOTP THERE) and is brokered into the
     * citizen realm; returns the citizen realm's access token. Creates the department user first.
     */
    public static String brokeredCitizenAccessToken(String departmentUser, String localIdType, String localId)
            throws Exception {
        String password = departmentUser + "-Pw-1";
        String totpSecret = departmentUser + "-totp-secret";
        importUser(DEPARTMENT, departmentUser, password, totpSecret, "\"default-roles-samanvay-department\"",
                Map.of("local_id_type", localIdType, "local_id", localId));
        BrowserLogin login = BrowserLogin.brokered(CITIZEN, "samanvay-citizen-ui", BROKER_ALIAS);
        login.submit(Map.of("username", departmentUser, "password", password));
        login.submit(Map.of("otp", totp(totpSecret)));
        login.follow();
        return login.accessToken();
    }

    private static String mailpitUrl() {
        keycloak();
        return "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025);
    }

    /**
     * The newest sign-in code mailed to {@code address} after {@code sentAfter}
     * (Mailpit API), waiting up to 15s for it to arrive.
     */
    public static String mailedCode(String address, java.time.Instant sentAfter) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> res = HTTP.send(
                    HttpRequest.newBuilder(URI.create(mailpitUrl() + "/api/v1/search?query="
                                    + URLEncoder.encode("to:\"" + address + "\"", StandardCharsets.UTF_8)))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            for (JsonNode m : JSON.readTree(res.body()).path("messages")) {
                java.time.Instant created = java.time.Instant.parse(m.get("Created").asString());
                if (!created.isBefore(sentAfter.minusSeconds(1))) {
                    String text = JSON.readTree(HTTP.send(
                                            HttpRequest.newBuilder(URI.create(mailpitUrl() + "/api/v1/message/"
                                                            + m.get("ID").asString()))
                                                    .GET().build(),
                                            HttpResponse.BodyHandlers.ofString())
                                    .body())
                            .get("Text").asString();
                    Matcher code = Pattern.compile("sign-in code is (\\d{6})").matcher(text);
                    if (code.find()) {
                        return code.group(1);
                    }
                }
            }
            Thread.sleep(250);
        }
        throw new AssertionError("no sign-in code mailed to " + address);
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

    public static int adminPut(String path, String json) throws Exception {
        HttpResponse<String> res = HTTP.send(
                HttpRequest.newBuilder(URI.create(keycloak().getAuthServerUrl() + path))
                        .header("Authorization", "Bearer " + adminToken())
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as("PUT " + path + " -> " + res.body()).isBetween(200, 204);
        return res.statusCode();
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
        /** Where this login starts: the shared container's realm, or any other Keycloak's (see {@link #at}). */
        private final String issuerUrl;
        private final String clientId;
        private final String verifier;
        private HttpResponse<String> last;

        public BrowserLogin(String realm, String clientId) throws Exception {
            this(realm, clientId, null);
        }

        /**
         * Starts at {@code realm}'s login with {@code kc_idp_hint=<idpHint>}: Keycloak redirects to the
         * brokered identity provider, whose login page (another realm) is then the current page.
         */
        public static BrowserLogin brokered(String realm, String clientId, String idpHint) throws Exception {
            return new BrowserLogin(realm, clientId, idpHint);
        }

        /** A browser login against a Keycloak other than the shared container, given its realm's issuer URL. */
        public static BrowserLogin at(String issuerUrl, String clientId) throws Exception {
            return new BrowserLogin(null, issuerUrl, clientId, null);
        }

        private BrowserLogin(String realm, String clientId, String idpHint) throws Exception {
            this(realm, issuer(realm), clientId, idpHint);
        }

        private BrowserLogin(String realm, String issuerUrl, String clientId, String idpHint) throws Exception {
            this.realm = realm;
            this.issuerUrl = issuerUrl;
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
            if (idpHint != null) {
                q.put("kc_idp_hint", idpHint);
            }
            last = client.send(
                    HttpRequest.newBuilder(URI.create(issuerUrl + "/protocol/openid-connect/auth?" + form(q))).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (idpHint != null) {
                follow(); // citizen realm -> redirector -> the brokered IdP's login page
            }
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

        /** Follows Keycloak-internal redirects (not the final one back to the UI). */
        public BrowserLogin follow() throws Exception {
            for (int hops = 0; isRedirect() && !finished(); hops++) {
                assertThat(hops).as("redirect loop; last Location " + last.headers().firstValue("Location")).isLessThan(25);
                String location = last.headers().firstValue("Location").orElseThrow();
                if (location.startsWith(UI_REDIRECT)) {
                    break;
                }
                last = client.send(HttpRequest.newBuilder(URI.create(location)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
            }
            return this;
        }

        /**
         * Keycloak answers with 302 in places and 303 See Other in others (the identity-provider redirector
         * and the broker endpoints use 303), so any redirect status counts.
         */
        private boolean isRedirect() {
            return switch (last.statusCode()) {
                case 301, 302, 303, 307, 308 -> true;
                default -> false;
            };
        }

        public HttpResponse<String> page() {
            return last;
        }

        /** True once Keycloak redirected back to the UI with an authorization code. */
        public boolean finished() {
            return isRedirect()
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
        /** "path\0name" -> value: two realms both set AUTH_SESSION_ID, told apart only by their Path. */
        private final Map<String, String> cookies = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public Map<String, java.util.List<String>> get(URI uri, Map<String, java.util.List<String>> headers) {
            String requestPath = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
            String header = cookies.entrySet().stream()
                    .filter(e -> requestPath.startsWith(e.getKey().substring(0, e.getKey().indexOf('\0'))))
                    .map(e -> e.getKey().substring(e.getKey().indexOf('\0') + 1) + "=" + e.getValue())
                    .collect(Collectors.joining("; "));
            return header.isEmpty() ? Map.of() : Map.of("Cookie", java.util.List.of(header));
        }

        @Override
        public void put(URI uri, Map<String, java.util.List<String>> headers) {
            headers.forEach((name, values) -> {
                if (name != null && name.equalsIgnoreCase("Set-Cookie")) {
                    for (String v : values) {
                        String[] parts = v.split(";");
                        String pair = parts[0];
                        String path = "/";
                        for (int i = 1; i < parts.length; i++) {
                            String attr = parts[i].trim();
                            if (attr.regionMatches(true, 0, "Path=", 0, 5)) {
                                path = attr.substring(5).trim();
                            }
                        }
                        int eq = pair.indexOf('=');
                        if (eq > 0) {
                            String key = path + '\0' + pair.substring(0, eq).trim();
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
