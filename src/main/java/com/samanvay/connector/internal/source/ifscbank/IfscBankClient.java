package com.samanvay.connector.internal.source.ifscbank;

import com.samanvay.connector.internal.source.ifscbank.IfscBankSourceException.Failure;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * HTTP client for the IFSC / bank-account source, written against the PUBLISHED
 * reference shapes (Razorpay IFSC API, Razorpay X account validation). The same
 * code talks to the department simulator and to the live source; only
 * {@link IfscBankSourceProperties} differ. There is no simulator-specific
 * branch here.
 *
 * <p>The simulator marker is only REPORTED ({@code simulatorMarker} on every
 * result/exception), never acted on. Its one planned use, in the mode-switch PR,
 * is a cross-check: in {@code live} mode a marked response is refused, audited
 * and alarmed. Badges come from the configured mode, not from this flag.
 */
public class IfscBankClient {

    public static final String MARKER_HEADER = "X-Samanvay-Simulator";
    public static final String MARKER_FIELD = "samanvay_simulator";

    private final IfscBankSourceProperties props;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    public IfscBankClient(IfscBankSourceProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build();
    }

    /** {@code GET {ifscBaseUrl}/{ifsc}}: 200 means a branch, 404 means no such IFSC (live answers 404 for malformed codes too). */
    public IfscLookup lookupIfsc(String ifsc) {
        URI uri = resolve(props.ifscBaseUrl(), "/" + ifsc.trim().toUpperCase(Locale.ROOT));
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri).GET());
        boolean marker = headerMarker(response);
        return switch (response.statusCode()) {
            case 200 -> {
                JsonNode body = parseObject(response, marker);
                yield new IfscLookup(Optional.of(IfscBranch.from(body)), marker || bodyMarker(body));
            }
            case 404 -> new IfscLookup(Optional.empty(), marker);
            default -> throw failureFor(response, marker);
        };
    }

    /**
     * {@code POST {baseUrl}/v1/fund_accounts/validations} (penny drop). A 400 is a
     * business answer (e.g. invalid IFSC), not a failure, so it comes back as a
     * rejected {@link AccountValidation}.
     */
    public AccountValidation validateAccount(String ifsc, String accountNumber, String holderName) {
        ObjectNode bankAccount = json.createObjectNode()
                .put("name", holderName)
                .put("ifsc", ifsc)
                .put("account_number", accountNumber);
        ObjectNode fundAccount = json.createObjectNode().put("account_type", "bank_account");
        fundAccount.set("bank_account", bankAccount);
        ObjectNode request = json.createObjectNode().put("amount", 100).put("currency", "INR");
        request.set("fund_account", fundAccount);

        HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(props.baseUrl(), "/v1/fund_accounts/validations"))
                .header("Content-Type", "application/json")
                .header("Authorization", basicAuth())
                .POST(HttpRequest.BodyPublishers.ofString(request.toString()));
        HttpResponse<String> response = send(builder);
        boolean marker = headerMarker(response);
        return switch (response.statusCode()) {
            case 200, 201 -> {
                JsonNode body = parseObject(response, marker);
                yield new AccountValidation(
                        text(body.get("id")),
                        text(body.get("status")),
                        text(body.at("/results/account_status")),
                        text(body.at("/results/registered_name")),
                        text(body.at("/status_details/reason")),
                        null,
                        marker || bodyMarker(body));
            }
            case 400 -> {
                JsonNode body = parseObject(response, marker);
                JsonNode error = body.get("error");
                if (error == null || !error.isObject()) {
                    throw new IfscBankSourceException(Failure.MALFORMED_RESPONSE, marker, "400 without error object", null);
                }
                yield new AccountValidation(
                        null, "rejected", null, null, text(error.get("description")), text(error.get("field")),
                        marker || bodyMarker(body));
            }
            default -> throw failureFor(response, marker);
        };
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) {
        try {
            return http.send(builder.timeout(props.readTimeout()).build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpConnectTimeoutException e) {
            throw new IfscBankSourceException(Failure.REMOTE_FAULT, false, "connect timeout", e);
        } catch (HttpTimeoutException e) {
            throw new IfscBankSourceException(Failure.TIMEOUT, false, "no response within " + props.readTimeout(), e);
        } catch (IOException e) {
            throw new IfscBankSourceException(Failure.REMOTE_FAULT, false, "I/O failure: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IfscBankSourceException(Failure.REMOTE_FAULT, false, "interrupted", e);
        }
    }

    private JsonNode parseObject(HttpResponse<String> response, boolean marker) {
        try {
            JsonNode body = json.readTree(response.body());
            if (body == null || !body.isObject()) {
                throw new IfscBankSourceException(Failure.MALFORMED_RESPONSE, marker, "body is not a JSON object", null);
            }
            return body;
        } catch (JacksonException e) {
            throw new IfscBankSourceException(Failure.MALFORMED_RESPONSE, marker, "unparseable body", e);
        }
    }

    private static IfscBankSourceException failureFor(HttpResponse<String> response, boolean marker) {
        int status = response.statusCode();
        Failure failure = status >= 500 ? Failure.REMOTE_FAULT
                : status == 401 || status == 403 ? Failure.AUTH_REJECTED
                : Failure.UNEXPECTED_STATUS;
        return new IfscBankSourceException(failure, marker, "HTTP " + status, null);
    }

    private String basicAuth() {
        String pair = nullToEmpty(props.keyId()) + ":" + nullToEmpty(props.keySecret());
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    private static URI resolve(URI base, String path) {
        String root = base.toString().endsWith("/") ? base.toString().substring(0, base.toString().length() - 1) : base.toString();
        return URI.create(root + path);
    }

    private static boolean headerMarker(HttpResponse<?> response) {
        return response.headers().firstValue(MARKER_HEADER).map("true"::equalsIgnoreCase).orElse(false);
    }

    private static boolean bodyMarker(JsonNode body) {
        JsonNode marker = body.get(MARKER_FIELD);
        return marker != null && marker.asBoolean(false);
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Result of an IFSC lookup; {@code branch} is empty when the source says the IFSC does not exist. */
    public record IfscLookup(Optional<IfscBranch> branch, boolean simulatorMarker) {}

    /** The subset of the Razorpay IFSC response the platform uses. */
    public record IfscBranch(
            String ifsc, String bank, String bankCode, String branch, String city, String district, String state,
            String micr, boolean neft, boolean rtgs, boolean imps, boolean upi) {

        static IfscBranch from(JsonNode b) {
            return new IfscBranch(
                    text(b.get("IFSC")), text(b.get("BANK")), text(b.get("BANKCODE")), text(b.get("BRANCH")),
                    text(b.get("CITY")), text(b.get("DISTRICT")), text(b.get("STATE")), text(b.get("MICR")),
                    flag(b, "NEFT"), flag(b, "RTGS"), flag(b, "IMPS"), flag(b, "UPI"));
        }

        private static boolean flag(JsonNode b, String name) {
            JsonNode n = b.get(name);
            return n != null && n.asBoolean(false);
        }
    }

    /**
     * Penny-drop outcome. {@code status} is the source's status ({@code completed},
     * {@code failed}, ...) or {@code rejected} for a 400; {@code rejectedField}
     * names the offending input on a 400 (e.g. {@code ifsc}).
     */
    public record AccountValidation(
            String validationId,
            String status,
            String accountStatus,
            String registeredName,
            String reason,
            String rejectedField,
            boolean simulatorMarker) {

        public boolean rejected() {
            return "rejected".equals(status);
        }

        public boolean accountActive() {
            return "active".equals(accountStatus);
        }

        /** Exact, case-insensitive comparison. Fuzzy name matching is out of scope for the skeleton. */
        public boolean registeredNameMatches(String suppliedName) {
            return registeredName != null && suppliedName != null
                    && registeredName.trim().equalsIgnoreCase(suppliedName.trim());
        }
    }
}
