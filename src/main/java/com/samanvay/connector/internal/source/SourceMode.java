package com.samanvay.connector.internal.source;

/**
 * How a source is configured. It drives two things: a {@code LIVE} source must
 * have its credential in SecretStore or the boot fails, and a {@code LIVE} source
 * that returns the simulator marker is refused, audited and alarmed (see
 * {@code IfscBankClient} and {@code ConnectorRuntime.bankCheck}). Base-URL
 * selection per mode and mode badges are still deferred (they need a config/UI
 * target); today the base URL is set directly.
 */
public enum SourceMode {
    SANDBOX,
    SIMULATOR,
    LIVE
}
