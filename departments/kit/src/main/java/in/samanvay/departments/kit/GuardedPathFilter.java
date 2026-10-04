package in.samanvay.departments.kit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Base of a department's credential filter (API key, bearer token, IP allow-list). It runs on EVERY request except a well-formed one
 * to a path on the public allow-list ({@link RequestPaths#isPublic}), and answers a malformed path with 400. A department therefore
 * cannot protect a path by forgetting to list it: it is protected unless it is explicitly public.
 */
public abstract class GuardedPathFilter extends OncePerRequestFilter {

    @Override
    protected final boolean shouldNotFilter(HttpServletRequest request) {
        return RequestPaths.isPublic(request);
    }

    @Override
    protected final void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (RequestPaths.malformed(request)) {
            RequestHygieneFilter.refuse(response);
            return;
        }
        check(request, RequestPaths.normalised(request), response, chain);
    }

    /** @param path the normalised path of a request that is not public */
    protected abstract void check(HttpServletRequest request, String path, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException;
}
