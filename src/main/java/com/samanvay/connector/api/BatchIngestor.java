package com.samanvay.connector.api;

public interface BatchIngestor {
    BatchResult ingest(String dataSourceCode);
}
