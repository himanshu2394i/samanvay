package com.samanvay.orchestration.internal.service;

import com.samanvay.shared.InvalidRequestException;

/**
 * Validates a passbook / cancelled-cheque upload by its <em>content</em>, not by
 * the declared filename or content-type (which a client controls). The accepted
 * types and size band follow the real scholarship portals: MahaDBT (PDF, up to
 * 256 KB) and NSP (PDF or JPEG, ~200 KB), widened to also accept PNG.
 *
 * <p>Only the first bytes are inspected (a magic-number sniff); nothing from the
 * content is echoed into the error, so a malformed upload can't leak its bytes.
 */
public final class PassbookUpload {

    static final int MIN_BYTES = 10 * 1024;   // reject empty / blur-tiny scans
    static final int MAX_BYTES = 256 * 1024;  // MahaDBT's ceiling

    public enum Kind {
        PDF(".pdf"),
        JPEG(".jpg"),
        PNG(".png");

        final String extension;
        Kind(String extension) { this.extension = extension; }
        public String extension() { return extension; }
    }

    private PassbookUpload() {}

    /** The detected kind, or a 400 {@link InvalidRequestException} — never a value from the content. */
    public static Kind validate(byte[] content) {
        if (content == null || content.length < MIN_BYTES) {
            throw new InvalidRequestException(
                    "the passbook file is empty or too small (min " + (MIN_BYTES / 1024) + " KB)");
        }
        if (content.length > MAX_BYTES) {
            throw new InvalidRequestException(
                    "the passbook file is larger than " + (MAX_BYTES / 1024) + " KB");
        }
        Kind kind = sniff(content);
        if (kind == null) {
            throw new InvalidRequestException("the passbook must be a PDF, JPEG or PNG file");
        }
        return kind;
    }

    private static Kind sniff(byte[] b) {
        if (startsWith(b, 0x25, 0x50, 0x44, 0x46)) {                          // %PDF
            return Kind.PDF;
        }
        if (startsWith(b, 0xFF, 0xD8, 0xFF)) {                                // JPEG SOI
            return Kind.JPEG;
        }
        if (startsWith(b, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {  // PNG signature
            return Kind.PNG;
        }
        return null;
    }

    private static boolean startsWith(byte[] b, int... magic) {
        if (b.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((b[i] & 0xFF) != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
