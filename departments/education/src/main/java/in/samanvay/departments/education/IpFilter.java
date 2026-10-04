package in.samanvay.departments.education;

import in.samanvay.departments.kit.GuardedPathFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * If {@code education.allowed-ips} is set, the caller's socket address must be on that list for everything that is not on the kit's
 * explicit public list (the SOAP service and anything added later: 403 otherwise, before the message is read); empty means no
 * restriction. The public manifest, the portal and login are never restricted. The decision is made on the normalised path, and a
 * path with {@code ;} parameters or other tricks is refused with 400 ({@link GuardedPathFilter}). The address is the socket peer,
 * never {@code X-Forwarded-For} (spoofable).
 */
@Component
class IpFilter extends GuardedPathFilter {

    private final List<String> allowedIps;

    IpFilter(@Value("${education.allowed-ips:}") List<String> allowedIps) {
        this.allowedIps = allowedIps.stream().map(String::trim).filter(ip -> !ip.isEmpty()).toList();
    }

    @Override
    protected void check(HttpServletRequest request, String path, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (allowedIps.isEmpty() || allowedIps.contains(request.getRemoteAddr())) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("text/plain");
        response.getWriter().write("caller address not allowed");
    }
}
