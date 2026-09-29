package in.samanvay.simulators.ifscbank;

import in.samanvay.simulators.ifscbank.IfscBankService.Fault;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Samanvay bank-check contract v1 ({@code docs/contracts/bank-check-v1.yaml}):
 *
 * <ul>
 *   <li>{@code GET /{ifsc}}: public IFSC lookup (open RBI data, keys as in the razorpay/ifsc dataset).
 *   <li>{@code POST /v1/bank-checks}: one-call account check, HTTP Basic.
 * </ul>
 *
 * <p>Fault injection: the fixture triggers in {@code fixtures/ifsc-bank.json} (the
 * caller needs no simulator-specific code), or the header {@value #FAULT_HEADER}:
 * {@code timeout | server_error | malformed}. Fault bodies are fixed text and never
 * echo request or fixture data.
 */
@RestController
class IfscBankController {

    static final String FAULT_HEADER = "X-Samanvay-Simulator-Fault";

    private final IfscBankService service;
    private final Duration timeoutDelay;

    IfscBankController(IfscBankService service, @Value("${simulator.faults.timeout-delay:30s}") Duration timeoutDelay) {
        this.service = service;
        this.timeoutDelay = timeoutDelay;
    }

    @GetMapping(path = "/{ifsc:[A-Za-z0-9]+}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> lookupIfsc(
            @PathVariable String ifsc, @RequestHeader(name = FAULT_HEADER, required = false) String faultHeader) {
        Fault fault = Fault.fromHeader(faultHeader).orElseGet(() -> service.ifscFault(ifsc).orElse(null));
        return fault == null ? service.lookupIfsc(ifsc) : faulted(fault, () -> service.lookupIfsc(ifsc));
    }

    @PostMapping(path = "/v1/bank-checks", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> bankCheck(
            @RequestBody JsonNode request, @RequestHeader(name = FAULT_HEADER, required = false) String faultHeader) {
        Fault fault = Fault.fromHeader(faultHeader).orElseGet(() -> service.accountFault(request).orElse(null));
        return fault == null ? service.bankCheck(request) : faulted(fault, () -> service.bankCheck(request));
    }

    private ResponseEntity<?> faulted(Fault fault, Supplier<ResponseEntity<?>> normal) {
        return switch (fault) {
            case TIMEOUT -> {
                // Holds the request past any sane client read timeout, then answers normally.
                try {
                    Thread.sleep(timeoutDelay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                yield normal.get();
            }
            case SERVER_ERROR -> IfscBankService.problemResponse(IfscBankService.problem(
                    HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable", "The bank check service is temporarily unavailable."));
            // 200 + JSON content type + a truncated body: the classic partial-write failure.
            case MALFORMED -> ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"" + IfscBankService.MARKER_FIELD + "\":true,\"accountStatus\":\"VAL");
        };
    }
}
