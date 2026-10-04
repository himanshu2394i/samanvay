package in.samanvay.departments.kit;

/** Samanvay refused a call or could not be reached. {@code status} is Samanvay's HTTP status, or 503 when it could not be reached. */
public class SamanvayException extends RuntimeException {

    private final int status;

    public SamanvayException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
