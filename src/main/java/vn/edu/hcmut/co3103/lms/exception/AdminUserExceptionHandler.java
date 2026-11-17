package vn.edu.hcmut.co3103.lms.exception;

import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import vn.edu.hcmut.co3103.lms.controller.admin.AdminUserController;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Administrative contracts only: existing controllers retain the main global advice unchanged. */
@RestControllerAdvice(assignableTypes = AdminUserController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminUserExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(AdminAccessValidationException.class)
    public ResponseEntity<Object> handleAccessValidation() {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed", List.of(), new HttpHeaders());
    }

    @ExceptionHandler(LastAdminProtectionException.class)
    public ResponseEntity<Object> handleLastAdmin(LastAdminProtectionException exception) {
        return error(HttpStatus.CONFLICT, "INVALID_ROLE_OR_STATUS_TRANSITION", exception.getMessage(), List.of(), new HttpHeaders());
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Object> handleUserNotFound(UserNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "LOCAL_USER_NOT_FOUND", exception.getMessage(), List.of(), new HttpHeaders());
    }

    @ExceptionHandler({IllegalArgumentException.class, PropertyReferenceException.class})
    public ResponseEntity<Object> handleInvalidArgument(Exception exception) {
        return hasAccessValidation(exception) ? handleAccessValidation()
                : error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Bad Request", List.of(), new HttpHeaders());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handlePersistenceFailure(DataIntegrityViolationException exception) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                "An unexpected server error occurred.", List.of(), new HttpHeaders());
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException exception, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        return hasAccessValidation(exception) ? handleAccessValidation()
                : error(status, "BAD_REQUEST", "Bad Request", List.of(), headers);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var details = exception.getBindingResult().getAllErrors().stream().map(e -> Map.of(
                "field", e instanceof FieldError field ? field.getField() : e.getObjectName(),
                "message", Objects.requireNonNullElse(e.getDefaultMessage(), "Invalid value"))).toList();
        return error(status, "BAD_REQUEST", "Validation failed", details, headers);
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return frameworkFailure(exception, headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
                                                            HttpStatusCode status, WebRequest request) {
        return frameworkFailure(exception, headers, status);
    }

    /** Handler mapping can reject a method/media type before a controller is selected. */
    static boolean isAdminUserRequest(WebRequest request) {
        if (!(request instanceof ServletWebRequest servlet)) return false;
        var http = servlet.getRequest();
        String path = http.getRequestURI().substring(http.getContextPath().length());
        return path.equals("/api/v1/admin/users") || path.startsWith("/api/v1/admin/users/")
                || path.equals("/api/admin/users") || path.startsWith("/api/admin/users/");
    }

    static ResponseEntity<Object> frameworkFailure(Exception exception, HttpHeaders headers, HttpStatusCode status) {
        if (hasAccessValidation(exception)) {
            return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed", List.of(), headers);
        }
        var responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        if (exception instanceof HttpRequestMethodNotSupportedException method) {
            var methods = method.getSupportedHttpMethods();
            responseHeaders.set(HttpHeaders.ALLOW, AllowedMethods.header(methods == null ? List.of() : methods));
        }
        HttpStatus resolved = HttpStatus.resolve(status.value());
        if (resolved == null) resolved = HttpStatus.INTERNAL_SERVER_ERROR;
        return error(status, resolved.name(), resolved.is5xxServerError()
                ? "An unexpected server error occurred." : resolved.getReasonPhrase(), List.of(), responseHeaders);
    }

    private static boolean hasAccessValidation(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof AdminAccessValidationException) return true;
        }
        return false;
    }

    private static ResponseEntity<Object> error(HttpStatusCode status, String code, String message, List<?> details,
                                         HttpHeaders headers) {
        var responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(new GlobalExceptionHandler.ApiErrorResponse(code, message, details), responseHeaders, status);
    }
}
