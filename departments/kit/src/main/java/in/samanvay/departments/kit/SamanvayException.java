package in.samanvay.departments.kit;

/** Samanvay refused a call or could not be reached. {@code status} is Samanvay's HTTP status, or 503 when it could not be reached. */
public class SamanvayException extends RuntimeException {

    private final int status;
    private final String reason;

    public SamanvayException(int status, String message) {
        this(status, message, null);
    }

    /** @param reason Samanvay's machine-readable reason (for example {@code LINK_PROOF_INVALID}), or null */
    public SamanvayException(int status, String message, String reason) {
        super(message);
        this.status = status;
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }

    public int status() {
        return status;
    }
}
