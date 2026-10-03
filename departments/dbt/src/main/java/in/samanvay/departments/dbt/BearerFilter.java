package in.samanvay.departments.dbt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * DBT's security scheme: every {@code /v1} call needs a valid, unexpired bearer token from {@code /oauth/token}. If
 * {@code dbt.allowed-ips} is set, the caller's socket address must also be on that list for {@code /v1} and {@code /oauth}
 * (403 otherwise; the public manifest is never restricted). The address is the socket peer, never {@code X-Forwarded-For}.
 */
@Component
class BearerFilter extends OncePerRequestFilter {

    private final TokenService tokens;
    private final List<String> allowedIps;

    BearerFilter(TokenService tokens, @Value("${dbt.allowed-ips:}") List<String> allowedIps) {
        this.tokens = tokens;
        this.allowedIps = allowedIps.stream().map(String::trim).filter(ip -> !ip.isEmpty()).toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/v1/") || path.startsWith("/oauth/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!allowedIps.isEmpty() && !allowedIps.contains(request.getRemoteAddr())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"caller address not allowed\"}");
            return;
        }
        if (request.getRequestURI().startsWith("/oauth/")) {
            chain.doFilter(request, response); // the token endpoint authenticates the client itself
            return;
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && tokens.isValid(header.substring(7).trim())) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"missing, invalid or expired bearer token\"}");
    }
}
