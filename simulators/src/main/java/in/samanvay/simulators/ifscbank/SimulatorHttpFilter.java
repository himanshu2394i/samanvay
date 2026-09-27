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
 * 2. Authenticates like the reference APIs: Razorpay X account validation uses
 *    HTTP Basic with an API key id + secret; the Razorpay IFSC API is public
 *    reference data and takes no credentials. So {@code /v1/**} requires Basic
 *    auth and IFSC lookup does not.
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
        if (request.getRequestURI().startsWith("/v1/") && !authorized(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"samanvay-simulator\"");
            // Razorpay's documented authentication-failure body.
            response.getWriter().write("{\"error\":{\"code\":\"BAD_REQUEST_ERROR\","
                    + "\"description\":\"The api key provided is invalid\",\"source\":\"NA\",\"step\":\"NA\","
                    + "\"reason\":\"NA\",\"metadata\":{}},\"" + IfscBankService.MARKER_FIELD + "\":true}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean authorized(String header) {
        return header != null && MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.UTF_8));
    }
}
