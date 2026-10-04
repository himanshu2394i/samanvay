package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.catalog.api.IllegalHostException;
import java.net.InetAddress;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The SSRF guard looks at the address a host really means: IP literals (bare or in brackets, with or without a port) are
 * judged directly, a name is judged by EVERY address it resolves to, and the private ranges include IPv6 unique-local,
 * link-local, carrier-grade NAT, multicast and the cloud metadata address.
 */
class DataSourceHostPolicyRangesTest {

    static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every name resolves to a public address, so only the literal itself can make a host private. */
    static final DataSourceHostPolicy PUBLIC_DNS = DataSourceHostPolicy.ofAll(h -> List.of(ip("93.184.216.34")), Set.of());

    @Test
    void bracketed_and_bare_ipv6_literals_in_private_ranges_are_refused() {
        for (String host : List.of("[::1]", "[::1]:8080", "[fd00::1]", "[fc00::1]:443", "[fe80::1]", "[::ffff:127.0.0.1]", "[::]", "[ff02::1]", "::1", "fd00::1")) {
            assertThatThrownBy(() -> PUBLIC_DNS.assertAllowed(host)).as(host).isInstanceOf(IllegalHostException.class);
        }
    }

    @Test
    void ipv4_private_cgnat_metadata_multicast_and_the_whole_172_16_to_31_range_are_refused() {
        for (String host : List.of("127.0.0.1", "10.1.2.3", "192.168.1.1", "169.254.169.254", "172.16.0.1", "172.20.1.1", "172.31.255.255", "100.64.0.1",
                "100.127.255.255", "224.0.0.1", "0.0.0.0", "0.1.2.3", "10.0.0.5:8443")) {
            assertThatThrownBy(() -> PUBLIC_DNS.assertAllowed(host)).as(host).isInstanceOf(IllegalHostException.class);
        }
    }

    @Test
    void public_addresses_just_outside_the_private_ranges_are_allowed() {
        for (String host : List.of("172.15.255.255", "172.32.0.1", "100.63.255.255", "100.128.0.1", "93.184.216.34", "[2606:4700::1111]", "dept.example.gov", "dept.example.gov:8443")) {
            assertThatCode(() -> PUBLIC_DNS.assertAllowed(host)).as(host).doesNotThrowAnyException();
        }
    }

    @Test
    void a_name_is_refused_when_any_of_its_addresses_is_private_not_only_the_first() {
        var policy = DataSourceHostPolicy.ofAll(h -> List.of(ip("93.184.216.34"), ip("fd00::5")), Set.of());
        assertThatThrownBy(() -> policy.assertAllowed("mixed.example.gov")).isInstanceOf(IllegalHostException.class);
        var cgnatSecond = DataSourceHostPolicy.ofAll(h -> List.of(ip("93.184.216.34"), ip("100.64.1.1")), Set.of());
        assertThatThrownBy(() -> cgnatSecond.assertAllowed("mixed2.example.gov")).isInstanceOf(IllegalHostException.class);
    }

    @Test
    void a_host_that_smuggles_credentials_or_a_path_is_refused() {
        for (String host : List.of("evil@169.254.169.254", "user:pw@dept.example.gov", "dept.example.gov/x", "dept.example.gov?x", "dept example.gov", "dept.example.gov\\x", "[::1")) {
            assertThatThrownBy(() -> PUBLIC_DNS.assertAllowed(host)).as(host).isInstanceOf(IllegalHostException.class);
        }
    }

    @Test
    void the_dev_allow_list_still_exempts_an_exact_host_including_a_bracketed_ipv6_one() {
        var policy = DataSourceHostPolicy.ofAll(h -> List.of(ip("::1")), Set.of("localhost", "::1"));
        assertThatCode(() -> policy.assertAllowed("localhost:8091")).doesNotThrowAnyException();
        assertThatCode(() -> policy.assertAllowed("[::1]:8091")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.assertAllowed("[fd00::1]")).isInstanceOf(IllegalHostException.class);
        org.assertj.core.api.Assertions.assertThat(policy.isDevExempt("LOCALHOST:1")).isTrue();
        org.assertj.core.api.Assertions.assertThat(policy.isDevExempt("dept.example.gov")).isFalse();
    }
}
