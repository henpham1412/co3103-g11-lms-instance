package vn.edu.hcmut.co3103.lms.service.adaptive;

import org.junit.jupiter.api.function.Executable;
import vn.edu.hcmut.co3103.lms.exception.AppException;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ErrorAssertions {
    private ErrorAssertions() { }

    static AppException assertError(ErrorCode expected, Executable action) {
        AppException exception = assertThrows(AppException.class, action);
        assertEquals(expected, exception.getErrorCode());
        return exception;
    }
}
