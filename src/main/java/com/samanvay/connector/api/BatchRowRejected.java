package com.samanvay.connector.api;

public record BatchRowRejected(String dataSourceCode, String filename, int rowNumber, String reason) {}
