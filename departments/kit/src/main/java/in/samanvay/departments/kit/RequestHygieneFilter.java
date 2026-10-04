package in.samanvay.departments.kit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * First in line for every request: a path with ';' parameters, encoded separators or dot segments is answered with 400 before any
 * other filter or handler sees it (see {@link RequestPaths}). The departments' own auth filters check the same thing again, so they
 * stay safe even if this one is not installed.
 */
public class RequestHygieneFilter extends OncePerRequestFilter implements Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (RequestPaths.malformed(request)) {
            refuse(response);
            return;
        }
        chain.doFilter(request, response);
    }

    static void refuse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"malformed request path\"}");
    }
}
