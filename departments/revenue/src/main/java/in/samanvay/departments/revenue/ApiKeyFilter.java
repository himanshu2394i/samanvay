package in.samanvay.departments.revenue;

import in.samanvay.departments.kit.GuardedPathFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Revenue's security scheme: every request that is not on the kit's explicit public list (the citizen portal, login, the manifest
 * and health) must carry the shared API key in {@code X-Api-Key}, so a new endpoint is protected the day it is added. The decision is
 * made on the normalised path, and a path with {@code ;} parameters or other tricks is refused with 400 ({@link GuardedPathFilter}),
 * so {@code /v1;x=1/...} cannot slip past. The comparison is constant-time so the key cannot be guessed by timing.
 *
 * <p>If {@code revenue.allowed-ips} is set, the caller's address must also be on that list (403 otherwise);
 * empty means no IP restriction. The address is the socket peer, never {@code X-Forwarded-For} (spoofable).
 *
 * <p>ponytail: one shared key; per-caller keys when more than Samanvay calls. Behind a trusted proxy the
 * allow-list must be applied at the proxy instead.
 */
@Component
class ApiKeyFilter extends GuardedPathFilter {

    static final String HEADER = "X-Api-Key";

    private final byte[] expected;
    private final List<String> allowedIps;

    ApiKeyFilter(@Value("${revenue.api-key}") String apiKey, @Value("${revenue.allowed-ips:}") List<String> allowedIps) {
        this.expected = apiKey.getBytes(StandardCharsets.UTF_8);
        this.allowedIps = allowedIps.stream().map(String::trim).filter(ip -> !ip.isEmpty()).toList();
    }

    @Override
    protected void check(HttpServletRequest request, String path, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!allowedIps.isEmpty() && !allowedIps.contains(request.getRemoteAddr())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"caller address not allowed\"}");
            return;
        }
        String given = request.getHeader(HEADER);
        if (given != null && MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"missing or invalid " + HEADER + "\"}");
    }
}
