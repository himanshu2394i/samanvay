package com.samanvay.shared;

public record BatchRowRejected(String dataSourceCode, String filename, int rowNumber, String reason) {}
