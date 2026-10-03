package com.samanvay.catalog.api;

import java.util.Map;

/**
 * Something onboarding cannot do for the admin and tells them to do: provision a secret, configure an SFTP or database
 * source, and so on. {@code kind} is PROVISION_SECRET, CONFIGURE_SFTP, CONFIGURE_JDBC or CONFIGURE_HTTPS; {@code subject}
 * is the data source it is about; {@code data} carries the exact names and values to use (never a secret value).
 */
public record PendingStep(String kind, String subject, String detail, Map<String, String> data) {}
