package vn.edu.hcmut.co3103.lms.exception;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        var supported = exception.getSupportedHttpMethods();
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaders.ALLOW, AllowedMethods.header(supported == null ? List.of() : supported))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "METHOD_NOT_ALLOWED", "message", "Method Not Allowed", "details", List.of()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedMediaType() {
        // Do not delegate request-derived media type parameters to the default logging resolver.
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "UNSUPPORTED_MEDIA_TYPE", "message", "Unsupported Media Type", "details", List.of()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            PropertyReferenceException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> handleMalformedRequest(Exception exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof AdminAccessValidationException) {
                return handleAdminAccessValidation();
            }
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "code", "BAD_REQUEST",
                        "message", "Bad Request",
                        "details", List.of()));
    }

    @ExceptionHandler(AdminAccessValidationException.class)
    public ResponseEntity<Map<String, Object>> handleAdminAccessValidation() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "VALIDATION_ERROR", "message", "Validation failed", "details", List.of()));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Map<String, Object>> handleNotAcceptable() {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "code", "NOT_ACCEPTABLE",
                        "message", "Not Acceptable",
                        "details", List.of()));
    }

    @ExceptionHandler(LastAdminProtectionException.class)
    public ResponseEntity<Map<String, Object>> handleLastAdminProtection(LastAdminProtectionException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "code", "INVALID_ROLE_OR_STATUS_TRANSITION",
                        "message", exception.getMessage(),
                        "details", List.of()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleUserNotFound(UserNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "code", "LOCAL_USER_NOT_FOUND",
                        "message", exception.getMessage(),
                        "details", List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {
        List<Map<String, String>> details = exception.getBindingResult().getAllErrors().stream()
                .map(error -> Map.of(
                        "field", error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName(),
                        "message", Objects.requireNonNullElse(error.getDefaultMessage(), "Invalid value")))
                .toList();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "code", "BAD_REQUEST",
                        "message", "Validation failed",
                        "details", details));
    }

    // Multipart parsing can fail before MVC has selected a controller.
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> handleMissingUploadPart() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "INVALID_FILE", "message", "The required upload file is missing.",
                        "details", List.of()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadTooLarge() {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "FILE_TOO_LARGE", "message", "The uploaded file exceeds the maximum allowed size.",
                        "details", List.of()));
    }
}
