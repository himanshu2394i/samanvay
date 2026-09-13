package com.samanvay.connector.api;

public record BatchResult(int processed, int rejected, boolean complete, int rowOffset) {}
