package in.samanvay.departments.kit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityHeadersFilterTest {

    MockHttpServletResponse call(String uri) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        new SecurityHeadersFilter().doFilter(new MockHttpServletRequest("GET", uri), res, new MockFilterChain());
        return res;
    }

    @Test
    void the_portal_api_is_never_cached_framed_or_sniffed() throws Exception {
        MockHttpServletResponse r = call("/portal-api/me");
        assertThat(r.getHeader("Cache-Control")).contains("no-store");
        assertThat(r.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void the_portal_pages_get_a_restrictive_content_security_policy() throws Exception {
        MockHttpServletResponse r = call("/portal/");
        assertThat(r.getHeader("Content-Security-Policy")).contains("default-src 'self'").contains("script-src 'self'").contains("frame-ancestors 'none'")
                .contains("base-uri 'self'").contains("object-src 'none'").doesNotContain("unsafe-eval");
        assertThat(r.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void the_login_pages_are_not_cached_and_may_not_run_scripts() throws Exception {
        for (String uri : new String[] {"/login", "/login/verify"}) {
            MockHttpServletResponse r = call(uri);
            assertThat(r.getHeader("Cache-Control")).as(uri).contains("no-store");
            assertThat(r.getHeader("X-Frame-Options")).as(uri).isEqualTo("DENY");
            assertThat(r.getHeader("X-Content-Type-Options")).as(uri).isEqualTo("nosniff");
            assertThat(r.getHeader("Content-Security-Policy")).as(uri).contains("default-src 'none'").contains("frame-ancestors 'none'")
                    .doesNotContain("form-action"); // a form-action rule would also block the redirect back to the asking department
        }
    }

    @Test
    void other_paths_are_left_alone() throws Exception {
        assertThat(call("/v1/income/1").getHeader("Cache-Control")).isNull();
    }
}
