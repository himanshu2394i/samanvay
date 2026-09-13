package com.samanvay.connector.api;

public class IllegalConnectorConfigurationException extends RuntimeException {
    public IllegalConnectorConfigurationException(String message) {
        super(message);
    }
}
