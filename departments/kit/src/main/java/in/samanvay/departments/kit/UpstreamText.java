package in.samanvay.departments.kit;

import java.util.regex.Pattern;

/**
 * Decides whether what Samanvay wrote in an error can be shown to a citizen. Samanvay's refusals are written as plain sentences
 * ("Connect your Revenue account first."), but an error that escaped from its inside can hold record IDs, table names, addresses or a
 * stack trace. Only a short sentence made of letters, digits and ordinary punctuation, with none of those telltales, goes through;
 * everything else is replaced by a generic line for the status.
 */
final class UpstreamText {

    private static final Pattern SENTENCE = Pattern.compile("[\\p{L}\\p{N} .,;:'\"()!?-]{1,199}");
    private static final Pattern INTERNAL = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-|exception|stack|\\bat [a-z]+\\.|\\bsql|jdbc|\\bselect\\b|\\binsert\\b|\\bconstraint\\b|\\bnull\\b|https?:");

    private UpstreamText() {}

    /** What a known machine reason means for a citizen. These are fixed sentences, so nothing from the inside can leak through them. */
    private static final java.util.Map<String, String> BY_REASON = java.util.Map.of(
            "JOURNEY_NOT_PUBLISHED", "This service is not open for applications yet. Please try again later.",
            "JOURNEY_ALREADY_OPEN", "You already have an application in progress for this service. Check your applications to follow it.",
            "CONSENT_REQUEST_NOT_PENDING", "That consent has already been used. Refresh the page and start again.",
            "CONSENT_TERMS_CHANGED", "The terms of this consent changed since you were shown them. Start again to review them.");

    static String forCitizen(int status, String upstream, String reason) {
        String known = reason == null ? null : BY_REASON.get(reason);
        if (known != null) {
            return known;
        }
        return forCitizen(status, upstream);
    }

    static String forCitizen(int status, String upstream) {
        String text = upstream == null ? "" : upstream.trim();
        if (SENTENCE.matcher(text).matches() && !INTERNAL.matcher(text).find()) {
            return text;
        }
        return switch (status) {
            case 400, 422 -> "Samanvay did not accept this request. Check the details and try again.";
            case 404 -> "Not found.";
            case 409 -> "This conflicts with the current state. Refresh and try again.";
            case 410 -> "This is no longer available.";
            case 429 -> "Too many requests. Please try again later.";
            default -> "Samanvay could not complete this request.";
        };
    }
}
