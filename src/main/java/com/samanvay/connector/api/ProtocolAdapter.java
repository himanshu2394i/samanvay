package com.samanvay.connector.api;

public interface ProtocolAdapter {
    String protocol();

    AdapterResponse execute(AdapterRequest request);
}
