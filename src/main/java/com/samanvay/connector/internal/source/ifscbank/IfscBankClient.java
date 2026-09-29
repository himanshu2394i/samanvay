package com.samanvay.connector.internal.source.ifscbank;

import com.samanvay.connector.api.BankCheckAdapter;
import com.samanvay.connector.api.SourceOutcome;
import com.samanvay.connector.api.SourceOutcome.Answered;
import com.samanvay.connector.api.SourceOutcome.ReasonCode;
import com.samanvay.connector.api.SourceOutcome.RequestRejected;
import com.samanvay.connector.api.SourceOutcome.SourceFault;
import com.samanvay.connector.api.SourceOutcome.SourceTimeout;
import com.samanvay.connector.internal.source.SourceCredentials;
import com.samanvay.connector.internal.source.SourceCredentials.Credential;
import com.samanvay.connector.internal.source.SourceMode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.UnexpectedEndOfInputException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link BankCheckAdapter} for source {@value #SOURCE_CODE}, speaking bank-check
 * contract v1 over HTTP. The same code talks to the department simulator and to
 * the live source (a future adapter maps PFMS or the department's own validation
 * onto the contract); only {@link IfscBankSourceProperties} and the SecretStore
 * credential differ. There is no simulator-specific branch. Stays in
 * connector.internal: callers use {@code BankCheckAdapters} (ArchUnit-enforced).
 *
 * <p>Returns a {@link SourceOutcome}, never throws for source behaviour. It knows
 * nothing of our REST layer. Failures carry fixed codes only: no body, no parser
 * message, no cause. Logs carry the operation and the outcome codes.
 *
 * <p>In simulator/sandbox mode the marker is only REPORTED. In {@code live} mode
 * a marked response is refused ({@link ReasonCode#MARKER_IN_LIVE_MODE}) and
 * alarmed here (ERROR log); {@code ConnectorRuntime.bankCheck} writes the audit
 * row. The answer is thrown away and never returned.
 */
public class IfscBankClient implements BankCheckAdapter {

    public static final String SOURCE_CODE = "ifsc-bank";
    public static final String MARKER_HEADER = "X-Samanvay-Simulator";
    public static final String MARKER_FIELD = "samanvay_simulator";

    private static final Logger log = LoggerFactory.getLogger(IfscBankClient.class);

    private final IfscBankSourceProperties props;
    private final SourceCredentials credentials;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    public IfscBankClient(IfscBankSourceProperties props, SourceCredentials credentials) {
        this.props = props;
        this.credentials = credentials;
        this.http = HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build();
    }

    @Override
    public String sourceCode() {
        return SOURCE_CODE;
    }

    /** {@code GET {ifscBaseUrl}/{ifsc}}: public; 200 = branch, 404 = no such IFSC (malformed codes too). */
    @Override
    public SourceOutcome<IfscAnswer> lookupIfsc(String ifsc) {
        URI uri = resolve(props.ifscBaseUrl(), "/" + ifsc.trim().toUpperCase(Locale.ROOT));
        return logged("ifsc-lookup", guardLiveMarker(exchange(HttpRequest.newBuilder(uri).GET(), (status, body, marker) -> switch (status) {
            case 200 -> parse(body, marker, b -> new IfscAnswer(Optional.of(branch(b))));
            case 404 -> new Answered<>(new IfscAnswer(Optional.empty()), marker);
            default -> statusFault(status, marker);
        })));
    }

    /** {@code POST {baseUrl}/v1/bank-checks}: one call, verdict only. */
    @Override
    public SourceOutcome<BankCheckAnswer> check(BankCheckRequest request) {
        Optional<Credential> credential = credentials.find(SOURCE_CODE);
        if (credential.isEmpty()) {
            return logged("bank-check", new SourceFault<>(ReasonCode.CREDENTIAL_MISSING, false));
        }
        String payload = json.createObjectNode()
                .put("ifsc", request.ifsc())
                .put("accountNumber", request.accountNumber())
                .put("applicantName", request.applicantName())
                .toString();
        HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(props.baseUrl(), "/v1/bank-checks"))
                .header("Content-Type", "application/json")
                .header("Authorization", basicAuth(credential.get()))
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        return logged("bank-check", guardLiveMarker(exchange(builder, (status, body, marker) -> switch (status) {
            case 200 -> parse(body, marker, this::answer);
            case 400 -> new RequestRejected<>(rejectedFields(body), marker);
            default -> statusFault(status, marker);
        })));
    }

    /**
     * A LIVE source must never carry the simulator marker. If it does, the answer
     * is thrown away and refused as {@link ReasonCode#MARKER_IN_LIVE_MODE}; the
     * ERROR-level alarm is raised in {@link #logged}. In any other mode the marker
     * is expected and the outcome is returned untouched.
     */
    private <T> SourceOutcome<T> guardLiveMarker(SourceOutcome<T> outcome) {
        if (props.mode() == SourceMode.LIVE && outcome.simulatorMarker()) {
            return new SourceFault<>(ReasonCode.MARKER_IN_LIVE_MODE, true);
        }
        return outcome;
    }

    @FunctionalInterface
    private interface Handler<T> {
        SourceOutcome<T> handle(int status, String body, boolean marker);
    }

    /** Thrown only inside parse(), for valid JSON that breaks contract v1. */
    private static final class ContractViolation extends RuntimeException {
        ContractViolation() {
            super(null, null, false, false);
        }
    }

    private <T> SourceOutcome<T> exchange(HttpRequest.Builder builder, Handler<T> handler) {
        HttpResponse<String> response;
        try {
            response = http.send(builder.timeout(props.readTimeout()).build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpConnectTimeoutException e) {
            return new SourceFault<>(ReasonCode.CONNECTION_FAILED, false);
        } catch (HttpTimeoutException e) {
            return new SourceTimeout<>();
        } catch (IOException e) {
            return new SourceFault<>(ReasonCode.CONNECTION_FAILED, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SourceFault<>(ReasonCode.CONNECTION_FAILED, false);
        }
        boolean marker = response.headers().firstValue(MARKER_HEADER).map("true"::equalsIgnoreCase).orElse(false);
        return handler.handle(response.statusCode(), response.body(), marker);
    }

    private <T> SourceOutcome<T> parse(String body, boolean headerMarker, java.util.function.Function<JsonNode, T> read) {
        JsonNode node;
        try {
            node = json.readTree(body);
        } catch (UnexpectedEndOfInputException e) {
            return new SourceFault<>(ReasonCode.TRUNCATED_BODY, headerMarker); // parser exception dropped on purpose
        } catch (JacksonException e) {
            return new SourceFault<>(ReasonCode.BAD_JSON, headerMarker);
        }
        if (node == null || !node.isObject()) {
            return new SourceFault<>(ReasonCode.BAD_JSON, headerMarker);
        }
        boolean marker = headerMarker || node.path(MARKER_FIELD).asBoolean(false);
        try {
            return new Answered<>(read.apply(node), marker);
        } catch (ContractViolation e) {
            return new SourceFault<>(ReasonCode.CONTRACT_VIOLATION, marker);
        }
    }

    private BankCheckAnswer answer(JsonNode body) {
        AccountStatus status = enumField(body, "accountStatus", AccountStatus.class);
        NameMatch nameMatch = enumField(body, "nameMatch", NameMatch.class);
        if (status != AccountStatus.VALID && nameMatch != NameMatch.NOT_CHECKED) {
            throw new ContractViolation();
        }
        return new BankCheckAnswer(status, nameMatch);
    }

    private static <E extends Enum<E>> E enumField(JsonNode body, String field, Class<E> type) {
        JsonNode node = body.get(field);
        if (node != null && node.isString()) {
            for (E value : type.getEnumConstants()) {
                if (value.name().equals(node.asString())) {
                    return value;
                }
            }
        }
        throw new ContractViolation();
    }

    private static IfscBranch branch(JsonNode b) {
        if (!b.path("IFSC").isString()) {
            throw new ContractViolation();
        }
        return new IfscBranch(
                text(b.get("IFSC")), text(b.get("BANK")), text(b.get("BANKCODE")), text(b.get("BRANCH")),
                text(b.get("CITY")), text(b.get("DISTRICT")), text(b.get("STATE")), text(b.get("MICR")),
                b.path("NEFT").asBoolean(false), b.path("RTGS").asBoolean(false),
                b.path("IMPS").asBoolean(false), b.path("UPI").asBoolean(false));
    }

    /** Field NAMES from a 400 problem's {@code invalidParams}; nothing else from the body is kept. */
    private List<String> rejectedFields(String body) {
        List<String> fields = new ArrayList<>();
        try {
            JsonNode params = json.readTree(body).get("invalidParams");
            if (params != null) {
                params.forEach(p -> {
                    String name = p.path("name").asString("");
                    if (name.matches("^[A-Za-z][A-Za-z0-9_]{0,63}$")) {
                        fields.add(name);
                    }
                });
            }
        } catch (JacksonException e) {
            // no field names
        }
        return fields;
    }

    private static <T> SourceOutcome<T> statusFault(int status, boolean marker) {
        ReasonCode code = status >= 500 ? ReasonCode.SERVER_ERROR
                : status == 401 || status == 403 ? ReasonCode.AUTH_REJECTED
                : ReasonCode.UNEXPECTED_STATUS;
        return new SourceFault<>(code, marker);
    }

    private static <T> SourceOutcome<T> logged(String operation, SourceOutcome<T> outcome) {
        switch (outcome) {
            case Answered<T> a when a.answer() instanceof BankCheckAnswer b ->
                    log.debug("ifsc-bank {}: accountStatus={} nameMatch={}", operation, b.accountStatus(), b.nameMatch());
            case Answered<T> a -> log.debug("ifsc-bank {}: answered", operation);
            case SourceTimeout<T> t -> log.warn("ifsc-bank {} failed: SourceTimeout", operation);
            case SourceFault<T> f when f.reasonCode() == ReasonCode.MARKER_IN_LIVE_MODE ->
                    log.error("ifsc-bank {} ALARM: a LIVE source returned the simulator marker; refusing the answer", operation);
            case SourceFault<T> f -> log.warn("ifsc-bank {} failed: SourceFault {}", operation, f.reasonCode());
            case RequestRejected<T> r -> log.warn("ifsc-bank {} failed: RequestRejected {}", operation, r.rejectedFields());
        }
        return outcome;
    }

    private static String basicAuth(Credential c) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((c.keyId() + ":" + c.keySecret()).getBytes(StandardCharsets.UTF_8));
    }

    private static URI resolve(URI base, String path) {
        String root = base.toString().endsWith("/") ? base.toString().substring(0, base.toString().length() - 1) : base.toString();
        return URI.create(root + path);
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asString();
    }
}
