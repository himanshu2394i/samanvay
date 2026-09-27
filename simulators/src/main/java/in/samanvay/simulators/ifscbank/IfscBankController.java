package in.samanvay.simulators.ifscbank;

import in.samanvay.simulators.ifscbank.IfscBankService.Fault;
import java.time.Duration;
import java.util.Map;
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
 * Two endpoints, each on the path of its public reference API so that a caller
 * only swaps the base URL between simulator and live:
 *
 * <ul>
 *   <li>{@code GET /{ifsc}} - Razorpay IFSC API (https://ifsc.razorpay.com/{ifsc}).
 *   <li>{@code POST /v1/fund_accounts/validations} - Razorpay X account validation
 *       (penny drop), https://api.razorpay.com/v1/fund_accounts/validations.
 * </ul>
 *
 * <p>Fault injection (so callers can exercise retry/timeout handling): either
 * the fixture-driven triggers in {@code fixtures/ifsc-bank.json} (a caller
 * needs no simulator-specific code for these), or the request header
 * {@value #FAULT_HEADER}: {@code timeout | server_error | malformed}.
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
        if (fault != null) {
            return faulted(fault, () -> service.lookupIfsc(ifsc));
        }
        return service.lookupIfsc(ifsc);
    }

    @PostMapping(
            path = "/v1/fund_accounts/validations",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> validateAccount(
            @RequestBody JsonNode request, @RequestHeader(name = FAULT_HEADER, required = false) String faultHeader) {
        Fault fault = Fault.fromHeader(faultHeader).orElseGet(() -> service.accountFault(request).orElse(null));
        if (fault != null) {
            return faulted(fault, () -> service.validateAccount(request));
        }
        return service.validateAccount(request);
    }

    private ResponseEntity<?> faulted(Fault fault, java.util.function.Supplier<ResponseEntity<?>> normal) {
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
            // Razorpay's documented 5xx error envelope.
            case SERVER_ERROR -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "error",
                            Map.of(
                                    "code", "SERVER_ERROR",
                                    "description", "The server encountered an error. The incident has been reported to admins."),
                            IfscBankService.MARKER_FIELD,
                            true));
            // 200 + JSON content type + a truncated body: the classic partial-write failure.
            case MALFORMED -> ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"" + IfscBankService.MARKER_FIELD + "\":true,\"IFSC\":\"SAMS00");
        };
    }
}
