package com.samanvay.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiAccessAuditFilterTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<Object> published = new ArrayList<>();
    private final ApiAccessAuditFilter filter =
            new ApiAccessAuditFilter(published::add, rq -> "/api/journeys/instances/{id}/retry", meters);

    @Test
    void forbiddenCallIsPublishedWithCallerAndRouteTemplate() throws Exception {
        var req = new MockHttpServletRequest("POST", "/api/journeys/instances/ATTACKER-<script>/retry");
        filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> {
            rq.setAttribute(ApiAccessAuditFilter.CALLER_ATTRIBUTE, new Caller("cit-1", "j", Set.of("CITIZEN"), Set.of()));
            ((MockHttpServletResponse) rs).setStatus(403);
        });
        assertThat(published).singleElement().isEqualTo(new ApiAccessRefused(
                403, "POST /api/journeys/instances/{id}/retry", "CITIZEN", "cit-1", "FORBIDDEN"));
    }

    @Test
    void anonymous401IsCountedNotPublished() throws Exception {
        for (int i = 0; i < 3; i++) {
            filter.doFilter(new MockHttpServletRequest("POST", "/api/journeys/instances/x/retry"),
                    new MockHttpServletResponse(), (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(401));
        }
        assertThat(published).isEmpty();
        assertThat(meters.counter(ApiAccessAuditFilter.UNAUTHENTICATED_METRIC,
                                "route", "POST /api/journeys/instances/{id}/retry", "reason", "UNAUTHENTICATED")
                        .count())
                .isEqualTo(3.0);
    }

    @Test
    void recordingFailureNeverBreaksTheRefusalResponse() {
        var failing = new ApiAccessAuditFilter(event -> {
            throw new IllegalStateException("audit down");
        }, rq -> "/api/audit/head", meters);
        var res = new MockHttpServletResponse();
        assertThatCode(() -> failing.doFilter(new MockHttpServletRequest("GET", "/api/audit/head"), res, (rq, rs) -> {
                    ((MockHttpServletResponse) rs).setStatus(403);
                    rs.getWriter().write("{\"status\":403}");
                }))
                .doesNotThrowAnyException();
        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    void successAndNonApiPathsAreNotRecorded() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/api/audit/head"), new MockHttpServletResponse(), (rq, rs) -> {});
        filter.doFilter(new MockHttpServletRequest("GET", "/index.html"), new MockHttpServletResponse(),
                (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(403));
        assertThat(published).isEmpty();
        assertThat(meters.getMeters()).isEmpty();
    }
}
