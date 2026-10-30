package vn.edu.hcmut.co3103.lms.exception;

import java.util.Objects;

/** One business exception type carrying a stable error code, not an HTTP response. */
public class AppException extends RuntimeException {
    private final ErrorCode errorCode;

    public AppException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public AppException(ErrorCode errorCode, Throwable cause) {
        super(Objects.requireNonNull(errorCode, "errorCode").getMessage(), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() { return errorCode; }
}
