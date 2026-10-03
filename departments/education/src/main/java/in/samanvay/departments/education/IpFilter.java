package in.samanvay.departments.education;

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
 * If {@code education.allowed-ips} is set, the caller's socket address must be on that list for the SOAP service (403
 * otherwise, before the message is read); empty means no restriction. The public manifest and login are never restricted.
 * The address is the socket peer, never {@code X-Forwarded-For} (spoofable).
 */
@Component
class IpFilter extends OncePerRequestFilter {

    private final List<String> allowedIps;

    IpFilter(@Value("${education.allowed-ips:}") List<String> allowedIps) {
        this.allowedIps = allowedIps.stream().map(String::trim).filter(ip -> !ip.isEmpty()).toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return allowedIps.isEmpty() || !request.getRequestURI().startsWith("/marks/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (allowedIps.contains(request.getRemoteAddr())) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("text/plain");
        response.getWriter().write("caller address not allowed");
    }
}
