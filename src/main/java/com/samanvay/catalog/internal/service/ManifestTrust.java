package com.samanvay.catalog.internal.service;

import com.samanvay.shared.InvalidRequestException;
import java.util.Optional;

/**
 * When Samanvay may rely on a department's manifest (docs/FINAL-CHANGES.md phase 6). {@code signedBy} is the thumbprint of the
 * key that signed it (empty = unsigned); {@code pinned} is the thumbprint an admin approved earlier (null = none yet).
 *
 * <ul>
 *   <li>Once a key is pinned the department can never go back to unsigned.
 *   <li>A key seen for the first time, or a changed key, is used only after an admin approves exactly that thumbprint.
 *   <li>Unsigned is accepted only where {@code samanvay.catalog.allow-unsigned-manifests} is on (tests, local dev).
 * </ul>
 */
final class ManifestTrust {

    private ManifestTrust() {}

    /** Can the admin review this manifest at all? */
    static void checkPlan(Optional<String> signedBy, String pinned, boolean allowUnsigned) {
        if (signedBy.isPresent()) {
            return;
        }
        if (pinned != null) {
            throw new InvalidRequestException("This department's manifest was signed before and is now unsigned, so it is refused. "
                    + "Ask the department to sign it again with the key you approved.");
        }
        if (!allowUnsigned) {
            throw new InvalidRequestException("The department's manifest is not signed, so it is refused. "
                    + "Ask the department to sign it (docs/contracts/manifest-signature.md).");
        }
    }

    /** Has the admin approved the key that signed what is about to be written? */
    static void checkOnboard(Optional<String> signedBy, String pinned, String approvedKey) {
        if (signedBy.isEmpty()) {
            return;
        }
        String key = signedBy.get();
        if (key.equals(pinned) || key.equals(approvedKey)) {
            return;
        }
        throw new InvalidRequestException((pinned == null
                ? "This department signs its manifest with a key you have not approved yet."
                : "This department's manifest signing key has changed since you approved one.")
                + " Confirm the key fingerprint " + key + " with the department, then approve it to continue.");
    }
}
