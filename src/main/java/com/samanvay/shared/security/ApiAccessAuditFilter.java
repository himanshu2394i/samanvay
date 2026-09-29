package com.samanvay.shared.security;

import com.samanvay.shared.PrincipalRef;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Wraps the whole Spring Security chain for {@code /api/**} and, after the
 * response status is known, records every refusal:
 *
 * <ul>
 *   <li><b>401</b> (no valid token - by definition anonymous): a log line and the
 *       {@value #UNAUTHENTICATED_METRIC} counter, <em>not</em> the audit chain.
 *       Anyone on the internet can produce these, and each chain append takes
 *       the global chain lock, so a burst of anonymous calls must cost neither
 *       chain rows nor lock time.
 *   <li><b>403</b> (a validated caller refused - by the route rules, an
 *       own-record check or a module's 403): {@link ApiAccessRefused}, which
 *       {@code audit} writes to the hash chain attributed to that caller - unless
 *       the module that refused it has already audited it (request attribute
 *       {@link ApiAccessRefused#AUDITED_ATTRIBUTE}): one audit row per refusal.
 * </ul>
 *
 * <p>Both record the matched <em>route template</em> (e.g.
 * {@code GET /api/journeys/instances/{id}}), never the raw URL, so request
 * paths chosen by the caller never reach the chain or the metric tags.
 *
 * <p>Recording is best-effort <em>for the response only</em>: the status and
 * body are already committed, and a failure is logged rather than turned into
 * a 500 for a request that was correctly refused. A denied request has no
 * business effect to roll back.
 */
final class ApiAccessAuditFilter extends OncePerRequestFilter {

    /** Set by {@link CaptureCallerFilter} inside the security chain, which clears its context on exit. */
    static final String CALLER_ATTRIBUTE = ApiAccessAuditFilter.class.getName() + ".caller";

    static final String UNAUTHENTICATED_METRIC = "samanvay.api.unauthenticated";

    private static final Logger log = LoggerFactory.getLogger(ApiAccessAuditFilter.class);

    private final ApplicationEventPublisher events;
    private final RouteTemplates routes;
    private final MeterRegistry meters;

    ApiAccessAuditFilter(ApplicationEventPublisher events, RouteTemplates routes, MeterRegistry meters) {
        this.events = events;
        this.routes = routes;
        this.meters = meters;
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
                record(request, status);
            }
        }
    }

    private void record(HttpServletRequest request, int status) {
        String route = RouteTemplates.UNMATCHED;
        try {
            route = request.getMethod() + " " + routes.templateOf(request);
            Object reasonAttribute = request.getAttribute(ProblemWriter.REASON_ATTRIBUTE);
            String reason = reasonAttribute == null
                    ? (status == 401 ? "UNAUTHENTICATED" : "FORBIDDEN")
                    : reasonAttribute.toString();
            if (status == 401) {
                meters.counter(UNAUTHENTICATED_METRIC, "route", route, "reason", reason).increment();
                log.info("refused unauthenticated API call: {} reason={}", route, reason);
                return;
            }
            if (Boolean.TRUE.equals(request.getAttribute(ApiAccessRefused.AUDITED_ATTRIBUTE))) {
                log.debug("refused API call already audited by its module: {} reason={}", route, reason);
                return;
            }
            String actorKind = ApiAccessRefused.ANONYMOUS;
            String actorId = "anonymous";
            if (request.getAttribute(CALLER_ATTRIBUTE) instanceof Caller caller) {
                if (caller.roles().isEmpty()) {
                    actorKind = ApiAccessRefused.AUTHENTICATED;
                    actorId = caller.subject();
                } else {
                    PrincipalRef p = caller.principal();
                    actorKind = p.kind().name();
                    actorId = p.id();
                }
            }
            events.publishEvent(new ApiAccessRefused(status, route, actorKind, actorId, reason));
        } catch (RuntimeException e) {
            log.warn("recording refused API call failed: {} -> {}", route, status, e);
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
