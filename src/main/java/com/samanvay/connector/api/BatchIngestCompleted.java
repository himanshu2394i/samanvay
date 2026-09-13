package com.samanvay.connector.api;

public record BatchIngestCompleted(String dataSourceCode, String filename, int processed, int rejected) {}
