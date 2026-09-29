package com.samanvay.notifications.api;

/**
 * A rendered notification handed to a {@link NotificationChannel}.
 *
 * @param recipientId the notification recipient id
 * @param body        the rendered body
 * @param address     the channel contact address resolved from the recipient's subscription (e-mail address for
 *                    EMAIL, phone number for SMS); {@code null} for channels that need none (IN_APP)
 * @param subject     a short subject line (used by EMAIL)
 */
public record RenderedMessage(String recipientId, String body, String address, String subject) {

    public RenderedMessage(String recipientId, String body) {
        this(recipientId, body, null, null);
    }
}
