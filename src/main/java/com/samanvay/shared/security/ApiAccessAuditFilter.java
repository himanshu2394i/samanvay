package com.samanvay.shared.security;

import com.samanvay.shared.PrincipalRef;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Wraps the whole Spring Security chain for {@code /api/**} and, after the
 * response status is known, publishes {@link ApiAccessRefused} for every
 * 401/403 - whether it came from the entry point, the access-denied handler,
 * an own-record check in a controller, or a module's 403 exception.
 *
 * <p>The audit write is best-effort <em>for the response only</em>: the
 * status and body are already committed, and an audit failure is logged
 * rather than turned into a 500 for a request that was correctly refused.
 * This is the one place where an audit failure does not propagate; a denied
 * request has no business effect to roll back.
 */
final class ApiAccessAuditFilter extends OncePerRequestFilter {

    /** Set by {@link CaptureCallerFilter} inside the security chain, which clears its context on exit. */
    static final String CALLER_ATTRIBUTE = ApiAccessAuditFilter.class.getName() + ".caller";

    private static final Logger log = LoggerFactory.getLogger(ApiAccessAuditFilter.class);

    private final ApplicationEventPublisher events;

    ApiAccessAuditFilter(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/api") || path.startsWith("/api/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            int status = response.getStatus();
            if (status == 401 || status == 403) {
                publish(request, status);
            }
        }
    }

    private void publish(HttpServletRequest request, int status) {
        try {
            String actorKind = ApiAccessRefused.ANONYMOUS;
            String actorId = "anonymous";
            if (request.getAttribute(CALLER_ATTRIBUTE) instanceof Caller caller) {
                if (caller.roles().isEmpty()) {
                    actorKind = "NO_ROLE";
                    actorId = caller.subject();
                } else {
                    PrincipalRef p = caller.principal();
                    actorKind = p.kind().name();
                    actorId = p.id();
                }
            }
            Object reason = request.getAttribute(ProblemWriter.REASON_ATTRIBUTE);
            events.publishEvent(new ApiAccessRefused(
                    status,
                    request.getMethod(),
                    request.getRequestURI(),
                    actorKind,
                    actorId,
                    reason == null ? (status == 401 ? "UNAUTHENTICATED" : "FORBIDDEN") : reason.toString()));
        } catch (RuntimeException e) {
            log.warn("audit of refused API call failed: {} {} -> {}", request.getMethod(), request.getRequestURI(), status, e);
        }
    }

    /** Runs inside the security chain, after bearer-token authentication. */
    static final class CaptureCallerFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof SamanvayAuthentication sa) {
                request.setAttribute(CALLER_ATTRIBUTE, sa.caller());
            }
            chain.doFilter(request, response);
        }
    }
}
