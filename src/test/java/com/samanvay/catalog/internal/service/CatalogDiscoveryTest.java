package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.catalog.api.IllegalHostException;
import com.samanvay.shared.InvalidRequestException;
import java.net.InetAddress;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** discover() validates the URL and blocks private/loopback targets (SSRF) before any network call. */
class CatalogDiscoveryTest {

    private CatalogServices svc(Function<String, InetAddress> resolver) {
        return new CatalogServices(null, null, null, null, null, null, null, e -> {}, resolver);
    }

    @Test
    void rejects_a_url_without_a_scheme_or_host() {
        assertThatThrownBy(() -> svc(h -> null).discover("dept.example.gov"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void requires_a_baseUrl() {
        assertThatThrownBy(() -> svc(h -> null).discover("   ")).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void blocks_localhost_and_hosts_resolving_to_private_addresses_before_any_fetch() {
        assertThatThrownBy(() -> svc(h -> InetAddress.getLoopbackAddress()).discover("http://localhost:9/x"))
                .isInstanceOf(IllegalHostException.class);
        // a public-looking host that resolves to a loopback/private address is still blocked (SSRF)
        assertThatThrownBy(() -> svc(h -> InetAddress.getLoopbackAddress()).discover("https://sneaky.example"))
                .isInstanceOf(IllegalHostException.class);
    }
}
