package in.samanvay.departments.kit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Browser-facing hardening for the citizen portal and the login pages: no framing, no content sniffing, no caching of anything
 * personal, and a content security policy.
 *
 * <p>The portal policy allows only this server's own scripts and connections. Its styles allow {@code 'unsafe-inline'} because the
 * portal sets the department's colours as inline styles; scripts never do. The login pages run no script at all. They have no
 * {@code form-action} rule on purpose: browsers also apply it to the redirect after a form post, and a login ends by redirecting to
 * the department that asked.
 */
public class SecurityHeadersFilter extends OncePerRequestFilter implements Ordered {

    static final String PORTAL_CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; "
            + "connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";
    static final String LOGIN_CSP = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'; frame-ancestors 'none'";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1; // after the hygiene filter: a refused path needs no headers
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = RequestPaths.normalised(request);
        boolean api = path.startsWith("/portal-api/");
        boolean page = path.equals("/portal") || path.startsWith("/portal/");
        boolean login = path.equals("/login") || path.startsWith("/login/");
        if (api || page || login) {
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("Referrer-Policy", "no-referrer");
        }
        if (api || login) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
        }
        if (page) {
            response.setHeader("Content-Security-Policy", PORTAL_CSP);
        } else if (login) {
            response.setHeader("Content-Security-Policy", LOGIN_CSP);
        }
        chain.doFilter(request, response);
    }
}
