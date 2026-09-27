package com.samanvay.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/** The route template a request matches, e.g. {@code /api/journeys/instances/{id}}. */
@FunctionalInterface
interface RouteTemplates {

    String UNMATCHED = "UNMATCHED";

    /** The matched template, or {@link #UNMATCHED}; never the raw request path. */
    String templateOf(HttpServletRequest request);

    /**
     * Resolves through the MVC handler mapping. Works for requests refused in
     * the security chain too, before the DispatcherServlet ever saw them.
     */
    static RouteTemplates from(RequestMappingHandlerMapping mapping) {
        return request -> {
            Object known = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (known != null) {
                return known.toString();
            }
            try {
                if (!ServletRequestPathUtils.hasParsedRequestPath(request)) {
                    ServletRequestPathUtils.parseAndCache(request);
                }
                HandlerExecutionChain handler = mapping.getHandler(request);
                Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                return handler != null && pattern != null ? pattern.toString() : UNMATCHED;
            } catch (Exception e) {
                return UNMATCHED; // e.g. method not supported on that path
            }
        };
    }
}
