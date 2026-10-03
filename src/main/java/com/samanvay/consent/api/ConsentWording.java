package com.samanvay.consent.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What a department portal must show the citizen, word for word, before the citizen confirms. {@code nonce} goes into the statement the
 * department signs; it is single use and ends at {@code expiresAt}.
 *
 * @param providers the departments whose records will be read, as {code, name}
 * @param validityDays how long the consent lasts once granted
 */
public record ConsentWording(
        UUID requestId,
        String purposeCode,
        String purposeText,
        List<String> categories,
        List<Provider> providers,
        int validityDays,
        String nonce,
        Instant expiresAt) {

    public record Provider(String code, String name) {}
}
