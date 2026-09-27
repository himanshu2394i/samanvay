package com.samanvay.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiAccessAuditFilterTest {

    @Test
    void refusedCallIsPublishedWithCallerFromToken() throws Exception {
        List<Object> published = new ArrayList<>();
        var filter = new ApiAccessAuditFilter(published::add);
        var req = new MockHttpServletRequest("POST", "/api/journeys/instances/x/retry");
        var res = new MockHttpServletResponse();
        filter.doFilter(req, res, (rq, rs) -> {
            rq.setAttribute(ApiAccessAuditFilter.CALLER_ATTRIBUTE, new Caller("cit-1", "j", Set.of("CITIZEN"), Set.of()));
            ((MockHttpServletResponse) rs).setStatus(403);
        });
        assertThat(published).singleElement().isEqualTo(new ApiAccessRefused(
                403, "POST", "/api/journeys/instances/x/retry", "CITIZEN", "cit-1", "FORBIDDEN"));
    }

    @Test
    void anonymous401IsPublished() throws Exception {
        List<Object> published = new ArrayList<>();
        var filter = new ApiAccessAuditFilter(published::add);
        var res = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/audit/head"), res, (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(401));
        assertThat(published).singleElement().extracting("actorKind", "actorId").containsExactly("ANONYMOUS", "anonymous");
    }

    @Test
    void auditFailureNeverBreaksTheRefusalResponse() {
        var filter = new ApiAccessAuditFilter(event -> {
            throw new IllegalStateException("audit down");
        });
        var res = new MockHttpServletResponse();
        assertThatCode(() -> filter.doFilter(new MockHttpServletRequest("GET", "/api/audit/head"), res, (rq, rs) -> {
                    ((MockHttpServletResponse) rs).setStatus(403);
                    rs.getWriter().write("{\"status\":403}");
                }))
                .doesNotThrowAnyException();
        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    void successAndNonApiPathsAreNotPublished() throws Exception {
        List<Object> published = new ArrayList<>();
        var filter = new ApiAccessAuditFilter(published::add);
        filter.doFilter(new MockHttpServletRequest("GET", "/api/audit/head"), new MockHttpServletResponse(), (rq, rs) -> {});
        filter.doFilter(new MockHttpServletRequest("GET", "/index.html"), new MockHttpServletResponse(),
                (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(403));
        assertThat(published).isEmpty();
    }
}
