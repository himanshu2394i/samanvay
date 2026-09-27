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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP client for Samanvay's bank-check contract v1 ({@code docs/contracts/bank-check-v1.yaml}).
 * The same code talks to the department simulator and to the live source (a
 * future adapter maps PFMS or the department's own validation onto the contract);
 * only {@link IfscBankSourceProperties} differ. There is no simulator-specific branch.
 *
 * <p>The simulator marker is only REPORTED ({@code simulatorMarker} on every
 * result/exception), never acted on. Its one planned use, in the mode-switch PR,
 * is a cross-check: in {@code live} mode a marked response is refused, audited
 * and alarmed. Badges come from the configured mode, not from this flag.
 *
 * <p>Privacy: the contract never returns the holder's name, and this client never
 * logs, stores or rethrows response bodies. Logs carry only the operation, the
 * status and the verdict enums.
 */
public class IfscBankClient {

    public static final String MARKER_HEADER = "X-Samanvay-Simulator";
    public static final String MARKER_FIELD = "samanvay_simulator";

    public enum AccountStatus { VALID, CLOSED, INVALID }

    public enum NameMatch { MATCH, PARTIAL, NO_MATCH, NOT_CHECKED }

    private static final Logger log = LoggerFactory.getLogger(IfscBankClient.class);

    private final IfscBankSourceProperties props;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    public IfscBankClient(IfscBankSourceProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build();
    }

    /** {@code GET {ifscBaseUrl}/{ifsc}}: public; 200 means a branch, 404 means no such IFSC (malformed codes too). */
    public IfscLookup lookupIfsc(String ifsc) {
        URI uri = resolve(props.ifscBaseUrl(), "/" + ifsc.trim().toUpperCase(Locale.ROOT));
        HttpResponse<String> response = send("ifsc-lookup", HttpRequest.newBuilder(uri).GET());
        boolean marker = headerMarker(response);
        return switch (response.statusCode()) {
            case 200 -> {
                JsonNode body = parseObject("ifsc-lookup", response, marker);
                yield new IfscLookup(Optional.of(IfscBranch.from(body)), marker || bodyMarker(body));
            }
            case 404 -> new IfscLookup(Optional.empty(), marker);
            default -> throw fail("ifsc-lookup", response, marker);
        };
    }

    /** {@code POST {baseUrl}/v1/bank-checks}: one call, verdict only. */
    public BankCheck check(String ifsc, String accountNumber, String applicantName) {
        String request = json.createObjectNode()
                .put("ifsc", ifsc)
                .put("accountNumber", accountNumber)
                .put("applicantName", applicantName)
                .toString();
        HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(props.baseUrl(), "/v1/bank-checks"))
                .header("Content-Type", "application/json")
                .header("Authorization", basicAuth())
                .POST(HttpRequest.BodyPublishers.ofString(request));
        HttpResponse<String> response = send("bank-check", builder);
        boolean marker = headerMarker(response);
        switch (response.statusCode()) {
            case 200 -> {
                JsonNode body = parseObject("bank-check", response, marker);
                AccountStatus status = enumField(body, "accountStatus", AccountStatus.class, marker);
                NameMatch nameMatch = enumField(body, "nameMatch", NameMatch.class, marker);
                if (status != AccountStatus.VALID && nameMatch != NameMatch.NOT_CHECKED) {
                    throw logged("bank-check", new IfscBankSourceException(
                            Failure.MALFORMED_RESPONSE, marker, "contract invariant broken: " + status + " with " + nameMatch, null));
                }
                BankCheck result = new BankCheck(status, nameMatch, marker || bodyMarker(body));
                log.debug("ifsc-bank bank-check: accountStatus={} nameMatch={}", status, nameMatch);
                return result;
            }
            case 400 -> {
                List<String> fields = new ArrayList<>();
                try {
                    JsonNode params = json.readTree(response.body()).get("invalidParams");
                    if (params != null) {
                        params.forEach(p -> fields.add(p.path("name").asString()));
                    }
                } catch (JacksonException e) {
                    // fall through with no field names
                }
                throw logged("bank-check", new IfscBankSourceException(
                        Failure.REQUEST_REJECTED, marker, "request rejected: " + fields, null, fields));
            }
            default -> throw fail("bank-check", response, marker);
        }
    }

    private HttpResponse<String> send(String operation, HttpRequest.Builder builder) {
        try {
            return http.send(builder.timeout(props.readTimeout()).build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpConnectTimeoutException e) {
            throw logged(operation, new IfscBankSourceException(Failure.REMOTE_FAULT, false, "connect timeout", e));
        } catch (HttpTimeoutException e) {
            throw logged(operation, new IfscBankSourceException(
                    Failure.TIMEOUT, false, "no response within " + props.readTimeout(), e));
        } catch (IOException e) {
            throw logged(operation, new IfscBankSourceException(Failure.REMOTE_FAULT, false, "I/O failure", e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw logged(operation, new IfscBankSourceException(Failure.REMOTE_FAULT, false, "interrupted", e));
        }
    }

    private JsonNode parseObject(String operation, HttpResponse<String> response, boolean marker) {
        JsonNode body;
        try {
            body = json.readTree(response.body());
        } catch (JacksonException e) {
            // No cause and no excerpt: parser messages can quote body text.
            throw logged(operation, new IfscBankSourceException(
                    Failure.MALFORMED_RESPONSE, marker, "unparseable body (" + e.getClass().getSimpleName() + ")", null));
        }
        if (body == null || !body.isObject()) {
            throw logged(operation, new IfscBankSourceException(
                    Failure.MALFORMED_RESPONSE, marker, "body is not a JSON object", null));
        }
        return body;
    }

    private <E extends Enum<E>> E enumField(JsonNode body, String field, Class<E> type, boolean marker) {
        JsonNode node = body.get(field);
        if (node != null && node.isString()) {
            for (E value : type.getEnumConstants()) {
                if (value.name().equals(node.asString())) {
                    return value;
                }
            }
        }
        throw logged("bank-check", new IfscBankSourceException(
                Failure.MALFORMED_RESPONSE, marker, "missing or unknown " + field, null));
    }

    private IfscBankSourceException fail(String operation, HttpResponse<String> response, boolean marker) {
        int status = response.statusCode();
        Failure failure = status >= 500 ? Failure.REMOTE_FAULT
                : status == 401 || status == 403 ? Failure.AUTH_REJECTED
                : Failure.UNEXPECTED_STATUS;
        return logged(operation, new IfscBankSourceException(failure, marker, "HTTP " + status, null));
    }

    private static IfscBankSourceException logged(String operation, IfscBankSourceException e) {
        log.warn("ifsc-bank {} failed: {} ({})", operation, e.failure(), e.getMessage());
        return e;
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

    /** The subset of the open RBI IFSC record the platform uses. */
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

    /** Bank-check verdict. By contract it carries no holder name, and neither does this record. */
    public record BankCheck(AccountStatus accountStatus, NameMatch nameMatch, boolean simulatorMarker) {}
}
