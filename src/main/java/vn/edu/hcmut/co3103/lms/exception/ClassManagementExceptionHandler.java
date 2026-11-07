package vn.edu.hcmut.co3103.lms.exception;

import jakarta.validation.ConstraintViolationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.controller.classmanagement.ClassController;

import java.util.List;
import java.util.Map;

@RestControllerAdvice(assignableTypes = ClassController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ClassManagementExceptionHandler {

    @ExceptionHandler(ClassCodeAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleClassCodeAlreadyExists(
            ClassCodeAlreadyExistsException exception
    ) {
        return response(HttpStatus.CONFLICT, "CLASS_CODE_ALREADY_EXISTS",
                "The class code is already in use.", List.of());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatusException(
            ResponseStatusException exception
    ) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String code = exception.getReason() == null ? status.name() : exception.getReason();
        String message = switch (status) {
            case BAD_REQUEST -> "The request contains invalid or unsupported values.";
            case NOT_FOUND -> "The requested resource was not found.";
            case FORBIDDEN -> "Access to this resource is denied.";
            case CONFLICT -> "The request conflicts with the current resource state.";
            default -> status.is5xxServerError()
                    ? "An unexpected server error occurred."
                    : "The request could not be completed.";
        };
        return response(status, code, message, List.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidArgument(
            IllegalArgumentException exception
    ) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "The request contains an unsupported or invalid value.", List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        List<Map<String, String>> details = exception.getBindingResult().getFieldErrors().stream()
                .map(this::fieldError)
                .toList();
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "One or more request fields are invalid.", details);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiErrorResponse> handleInvalidParameter(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_QUERY_PARAMETER",
                "A query parameter is invalid or unsupported.", List.of());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        List<Map<String, String>> details = exception.getConstraintViolations().stream()
                .map(violation -> Map.of(
                        "field", violation.getPropertyPath().toString(),
                        "code", "INVALID_VALUE"))
                .toList();
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "One or more request values are invalid.", details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableRequest(HttpMessageNotReadableException exception) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "The request body is malformed or contains unsupported values.", List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException exception
    ) {
        if (hasConstraint(exception, "uq_class_class_code")) {
            return response(HttpStatus.CONFLICT, "CLASS_CODE_ALREADY_EXISTS",
                    "The class code is already in use.", List.of());
        }
        return response(HttpStatus.CONFLICT, "RESOURCE_CONFLICT",
                "The request conflicts with existing data.", List.of());
    }

    private boolean hasConstraint(Throwable throwable, String constraint) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && constraint.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> fieldError(FieldError error) {
        return Map.of("field", error.getField(), "code", "INVALID_VALUE");
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status, String code, String message, List<?> details
    ) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(code, message, details));
    }

    public record ApiErrorResponse(String code, String message, List<?> details) {
    }
}
