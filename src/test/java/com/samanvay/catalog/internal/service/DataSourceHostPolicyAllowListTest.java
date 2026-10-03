package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.catalog.api.IllegalHostException;
import java.net.InetAddress;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Private and loopback hosts are refused (SSRF). Dev/demo may explicitly allow named hosts (the local department
 * stand-ins); the allow-list is by exact host name, never a pattern, and the default is empty.
 */
class DataSourceHostPolicyAllowListTest {

    static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    @Test
    void by_default_localhost_and_private_ranges_are_refused() {
        var policy = new DataSourceHostPolicy(h -> LOOPBACK);
        assertThatThrownBy(() -> policy.assertAllowed("localhost:8091")).isInstanceOf(IllegalHostException.class);
        assertThatThrownBy(() -> policy.assertAllowed("127.0.0.1")).isInstanceOf(IllegalHostException.class);
        assertThatThrownBy(() -> policy.assertAllowed("10.0.0.5")).isInstanceOf(IllegalHostException.class);
        assertThatThrownBy(() -> policy.assertAllowed("sneaky.example")).isInstanceOf(IllegalHostException.class);
    }

    @Test
    void an_explicitly_allowed_host_passes_with_or_without_a_port_and_in_any_case() {
        var policy = new DataSourceHostPolicy(h -> LOOPBACK, Set.of("localhost", "127.0.0.1"));
        assertThatCode(() -> policy.assertAllowed("localhost")).doesNotThrowAnyException();
        assertThatCode(() -> policy.assertAllowed("localhost:8091")).doesNotThrowAnyException();
        assertThatCode(() -> policy.assertAllowed("LOCALHOST:5434")).doesNotThrowAnyException();
        assertThatCode(() -> policy.assertAllowed("127.0.0.1:2223")).doesNotThrowAnyException();
    }

    @Test
    void only_the_listed_hosts_are_allowed_other_private_hosts_stay_refused() {
        var policy = new DataSourceHostPolicy(h -> LOOPBACK, Set.of("localhost"));
        assertThatThrownBy(() -> policy.assertAllowed("10.0.0.5")).isInstanceOf(IllegalHostException.class);
        assertThatThrownBy(() -> policy.assertAllowed("192.168.1.9")).isInstanceOf(IllegalHostException.class);
        assertThatThrownBy(() -> policy.assertAllowed("localhost.evil.example")).isInstanceOf(IllegalHostException.class);
        assertThatThrownBy(() -> policy.assertAllowed("evil-localhost")).isInstanceOf(IllegalHostException.class);
    }

    @Test
    void a_blank_host_is_still_refused() {
        var policy = new DataSourceHostPolicy(h -> LOOPBACK, Set.of("localhost"));
        assertThatThrownBy(() -> policy.assertAllowed("  ")).isInstanceOf(IllegalHostException.class);
        assertThatThrownBy(() -> policy.assertAllowed(null)).isInstanceOf(IllegalHostException.class);
    }
}
