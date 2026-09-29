package in.samanvay.simulators.ifscbank;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 1. Marks EVERY response with {@value #MARKER_HEADER}: true (including 401/404/5xx
 *    and malformed bodies), so a caller can always tell it reached a simulator.
 * 2. Authenticates per the bank-check contract: {@code /v1/bank-checks} requires HTTP
 *    Basic (key id + secret from config); IFSC lookup is open RBI data and
 *    takes no credentials.
 */
@Component
class SimulatorHttpFilter extends OncePerRequestFilter {

    static final String MARKER_HEADER = "X-Samanvay-Simulator";

    private final byte[] expected;

    SimulatorHttpFilter(
            @Value("${simulator.ifsc-bank.key-id}") String keyId,
            @Value("${simulator.ifsc-bank.key-secret}") String keySecret) {
        this.expected = ("Basic " + Base64.getEncoder()
                        .encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8)))
                .getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(MARKER_HEADER, "true");
        // Only the bank-check contract is authenticated; the sandbox department endpoints (/v1/income,
        // /marks/service) are open, as the main app's REST/SOAP adapters send no credential today.
        if (request.getRequestURI().startsWith("/v1/bank-checks") && !authorized(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"samanvay-simulator\"");
            response.getWriter().write("{\"type\":\"https://samanvay.dev/contracts/bank-check/v1/problems/unauthorized\","
                    + "\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Missing or invalid credentials.\",\""
                    + IfscBankService.MARKER_FIELD + "\":true}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean authorized(String header) {
        return header != null && MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.UTF_8));
    }
}
