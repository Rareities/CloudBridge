package ca.pkay.rcloneexplorer;

/** Typed, fixed-text failures safe to return across CloudBridge's document-provider boundary. */
public final class RcdExternalFailure {

    public enum Kind {
        JOB_NOT_FOUND("The requested rclone job is no longer available."),
        REQUEST_REJECTED("Rclone rejected the request."),
        REQUEST_TOO_LARGE("The rclone request exceeds the supported size limit."),
        REQUEST_ENCODING_FAILED("The rclone request could not be encoded."),
        INVALID_RESPONSE("Rclone returned an invalid response; verify remote state before retrying."),
        SERVER_FAILURE("Rclone returned a server error. The operation may have started; verify remote state before retrying."),
        TRANSPORT_FAILURE("Rclone did not confirm the request. The operation may have started; verify remote state before retrying."),
        UNKNOWN("Rclone operation failed.");

        private final String message;

        Kind(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }

    private final Kind kind;

    private RcdExternalFailure(Kind kind) {
        this.kind = kind;
    }

    static RcdExternalFailure fromResponse(String responseError, int status) {
        if (responseError != null && "job not found".equalsIgnoreCase(responseError.trim())) {
            return new RcdExternalFailure(Kind.JOB_NOT_FOUND);
        }
        if (status >= 500) return new RcdExternalFailure(Kind.SERVER_FAILURE);
        if (status >= 400) return new RcdExternalFailure(Kind.REQUEST_REJECTED);
        return new RcdExternalFailure(Kind.UNKNOWN);
    }

    static RcdExternalFailure transportFailure() {
        return new RcdExternalFailure(Kind.TRANSPORT_FAILURE);
    }

    static RcdExternalFailure requestTooLarge() {
        return new RcdExternalFailure(Kind.REQUEST_TOO_LARGE);
    }

    static RcdExternalFailure requestEncodingFailed() {
        return new RcdExternalFailure(Kind.REQUEST_ENCODING_FAILED);
    }

    static RcdExternalFailure invalidResponse() {
        return new RcdExternalFailure(Kind.INVALID_RESPONSE);
    }

    public Kind getKind() {
        return kind;
    }

    public String getMessage() {
        return kind.getMessage();
    }
}
