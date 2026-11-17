package vn.edu.hcmut.co3103.lms.exception;

public class AdminAccessValidationException extends RuntimeException {
    public AdminAccessValidationException() {
        super("Validation failed");
    }
}
