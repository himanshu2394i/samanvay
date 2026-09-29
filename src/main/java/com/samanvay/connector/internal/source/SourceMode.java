package com.samanvay.connector.internal.source;

/**
 * How a source is configured. In this PR it drives exactly one thing: a
 * {@code LIVE} source must have its credential in SecretStore, or the boot fails.
 * Base-URL selection and badges per mode come with the mode-switch PR.
 */
public enum SourceMode {
    SANDBOX,
    SIMULATOR,
    LIVE
}
