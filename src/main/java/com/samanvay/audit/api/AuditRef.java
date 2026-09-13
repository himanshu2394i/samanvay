package com.samanvay.audit.api;

public record AuditRef(long seq, byte[] hash) {}
