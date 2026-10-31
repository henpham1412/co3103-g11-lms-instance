package vn.edu.hcmut.co3103.lms.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ClassCodeAlreadyExistsException.class)
    public ResponseEntity<Map<String, String>> handleClassCodeAlreadyExists(
            ClassCodeAlreadyExistsException exception
    ) {
        Map<String, String> response = Map.of(
                "error", "CLASS_CODE_ALREADY_EXISTS",
                "message", exception.getMessage()
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatusException(
            ResponseStatusException exception
    ) {
        String errorCode = exception.getReason() == null
                ? "REQUEST_FAILED"
                : exception.getReason();
        Map<String, String> response = Map.of(
                "error", errorCode,
                "message", errorCode
        );

        return ResponseEntity.status(exception.getStatusCode()).body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleInvalidArgument(
            IllegalArgumentException exception
    ) {
        Map<String, String> response = Map.of(
                "error", "VALIDATION_ERROR",
                "message", "The request contains an unsupported or invalid value."
        );
        return ResponseEntity.badRequest().body(response);
    }
}
