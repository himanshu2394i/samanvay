package in.samanvay.departments.kit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The auth filters decide on a normalised path, never on the raw URI: ';' parameters, encoded and dot-segment tricks fail closed. */
class RequestPathsTest {

    @Test
    void path_parameters_encoded_separators_and_dot_segments_are_malformed() {
        for (String uri : new String[] {"/v1;x=1/persons/RV-1/documents", "/v1/..;x", "/v1%3Bx=1/a", "/v1%3bx/a", "/.well-known/samanvay/manifest;x",
                "/portal/../v1/income/1", "/portal/%2e%2e/v1/income/1", "/portal/%2E%2E/v1", "/a%2Fb", "/a%5Cb", "/a\\b", "/a//b", "/v1/./x",
                "/a%00b", "/a%zz"}) {
            assertThat(RequestPaths.malformed(uri)).as(uri).isTrue();
        }
        for (String uri : new String[] {"/", "/v1/income/INC-2026-0007", "/.well-known/samanvay/manifest", "/portal/", "/portal-api/me", "/login",
                "/portal/assets/index-4f2a.js", "/v1/persons/RV-1001/documents"}) {
            assertThat(RequestPaths.malformed(uri)).as(uri).isFalse();
        }
    }

    @Test
    void the_normalised_path_is_what_the_container_would_route_on() {
        assertThat(RequestPaths.normalised("/v1;x=1/persons")).isEqualTo("/v1/persons");
        assertThat(RequestPaths.normalised("/portal/../v1/income/1")).isEqualTo("/v1/income/1");
        assertThat(RequestPaths.normalised("/p%6Frtal/x")).isEqualTo("/portal/x");
        assertThat(RequestPaths.normalised("/v1/..;x")).isEqualTo("/");
        assertThat(RequestPaths.isPublic(RequestPaths.normalised("/a%zz"))).as("undecodable is never public").isFalse();
    }

    @Test
    void only_the_explicit_public_list_is_public() {
        for (String p : new String[] {"/", "/portal", "/portal/", "/portal/assets/a.js", "/portal-api/me", "/login", "/login/verify",
                "/.well-known/samanvay/manifest", "/.well-known/jwks.json", "/error", "/favicon.ico", "/actuator/health"}) {
            assertThat(RequestPaths.isPublic(p)).as(p).isTrue();
        }
        for (String p : new String[] {"/v1/income/1", "/v1", "/oauth/token", "/marks/service", "/portalx", "/loginx", "/portal-apix/a", "/actuator/env",
                "/.well-known", "/anything", "/V1/income"}) {
            assertThat(RequestPaths.isPublic(p)).as(p).isFalse();
        }
    }

    @Test
    void a_request_with_a_path_parameter_is_not_public_even_when_it_looks_like_the_portal() {
        assertThat(RequestPaths.isPublic(new MockHttpServletRequest("GET", "/portal;x/v1"))).isFalse();
        assertThat(RequestPaths.isPublic(new MockHttpServletRequest("GET", "/portal/index.html"))).isTrue();
    }

    @Test
    void the_hygiene_filter_answers_400_before_anything_else_sees_a_malformed_path() throws Exception {
        RequestHygieneFilter filter = new RequestHygieneFilter();
        for (String uri : new String[] {"/v1;x=1/income/1", "/v1%3Bx/income/1", "/.well-known/samanvay/manifest;x"}) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(new MockHttpServletRequest("GET", uri), res, chain);
            assertThat(res.getStatus()).as(uri).isEqualTo(400);
            assertThat(chain.getRequest()).as(uri + " must not reach the chain").isNull();
        }
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/v1/income/1"), new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }
}
