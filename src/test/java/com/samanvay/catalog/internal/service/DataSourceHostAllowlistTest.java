package com.samanvay.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samanvay.catalog.api.IllegalHostException;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class DataSourceHostAllowlistTest {

    @Test
    void rejectsPrivateAndMetadataHosts() throws Exception {
        DataSourceHostPolicy policy = new DataSourceHostPolicy(host -> {
            if ("internal.revenue.gov".equals(host)) {
                return InetAddress.getByAddress(new byte[] {10, 0, 0, 5});
            }
            return InetAddress.getByName(host);
        });
        assertThatThrownBy(() -> policy.assertAllowed("127.0.0.1")).isInstanceOf(IllegalHostException.class);
        assertThatThrownBy(() -> policy.assertAllowed("169.254.169.254")).isInstanceOf(IllegalHostException.class);
        assertThatThrownBy(() -> policy.assertAllowed("internal.revenue.gov")).isInstanceOf(IllegalHostException.class);
    }
}
