package com.samanvay.shared.test;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/** RestClient pre-authenticated with a {@link TestTokens} bearer token. */
public final class TestHttp {

    private TestHttp() {}

    public static RestClient as(String token) {
        return RestClient.builder()
                .defaultHeader(HttpHeaders.AUTHORIZATION, TestTokens.bearer(token))
                .build();
    }

    public static RestClient anonymous() {
        return RestClient.create();
    }
}
