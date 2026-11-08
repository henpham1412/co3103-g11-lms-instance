package vn.edu.hcmut.co3103.lms.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;

/** DOC-05 envelope with Spring MVC's status codes and protocol headers preserved. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<Map<String, Object>> handleAppException(AppException exception) {
        return response(exception.getErrorCode());
    }

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
            case CONTENT_TOO_LARGE -> "The uploaded file exceeds the maximum allowed size.";
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

    // Jakarta Bean Validation is a caller-input error, not a database constraint failure.
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<?> handleBeanValidation(
            ConstraintViolationException exception, WebRequest request) {
        if (isAdaptiveOrProbeRequest(request)) {
            return response(inputErrorCode(request));
        }
        List<Map<String, String>> details = exception.getConstraintViolations().stream()
                .map(violation -> Map.of(
                        "field", violation.getPropertyPath().toString(),
                        "code", "INVALID_VALUE"))
                .toList();
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "One or more request values are invalid.", details);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception, WebRequest request) {
        if (isAdaptiveOrProbeRequest(request)) {
            return response(inputErrorCode(request));
        }
        return response(HttpStatus.BAD_REQUEST, "INVALID_QUERY_PARAMETER",
                "A query parameter is invalid or unsupported.", List.of());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (isAdaptiveOrProbeRequest(request)) {
            return super.handleExceptionInternal(exception, envelope(inputErrorCode(request)),
                    jsonHeaders(headers), status, request);
        }
        List<Map<String, String>> details = exception.getBindingResult().getFieldErrors().stream()
                .map(this::fieldError)
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .headers(jsonHeaders(headers))
                .body(new ApiErrorResponse("VALIDATION_ERROR",
                        "One or more request fields are invalid.", details));
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (isAdaptiveOrProbeRequest(request)) {
            return super.handleExceptionInternal(exception, envelope(inputErrorCode(request)),
                    jsonHeaders(headers), status, request);
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .headers(jsonHeaders(headers))
                .body(new ApiErrorResponse("INVALID_QUERY_PARAMETER",
                        "A query parameter is invalid or unsupported.", List.of()));
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestPart(
            MissingServletRequestPartException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .headers(jsonHeaders(headers))
                .body(new ApiErrorResponse("INVALID_FILE",
                        "The required upload file is missing.", List.of()));
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .headers(jsonHeaders(headers))
                .body(new ApiErrorResponse("FILE_TOO_LARGE",
                        "The uploaded file exceeds the maximum allowed size.", List.of()));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (isAdaptiveOrProbeRequest(request)) {
            return super.handleExceptionInternal(exception, envelope(ErrorCode.UNSUPPORTED_MEDIA_TYPE),
                    jsonHeaders(headers), status, request);
        }
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .headers(jsonHeaders(headers))
                .body(new ApiErrorResponse("UNSUPPORTED_MEDIA_TYPE",
                        "The request uses an unsupported media type.", List.of()));
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (isAdaptiveOrProbeRequest(request)) {
            return super.handleExceptionInternal(exception, envelope(ErrorCode.METHOD_NOT_ALLOWED),
                    jsonHeaders(headers), status, request);
        }
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(jsonHeaders(headers))
                .body(new ApiErrorResponse("METHOD_NOT_ALLOWED",
                        "The HTTP method is not supported for this resource.", List.of()));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (isAdaptiveOrProbeRequest(request)) {
            return super.handleExceptionInternal(exception, envelope(inputErrorCode(request)),
                    jsonHeaders(headers), status, request);
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .headers(jsonHeaders(headers))
                .body(new ApiErrorResponse("VALIDATION_ERROR",
                        "The request body is malformed or contains unsupported values.", List.of()));
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (isAdaptiveOrProbeRequest(request)) {
            return super.handleExceptionInternal(exception, envelope(ErrorCode.RESOURCE_NOT_FOUND),
                    jsonHeaders(headers), status, request);
        }
        HttpHeaders responseHeaders = jsonHeaders(headers);
        responseHeaders.set(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8");
        responseHeaders.set(HttpHeaders.CACHE_CONTROL, "no-store");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .headers(responseHeaders)
                .body(new ApiErrorResponse("NOT_FOUND", "Not Found", List.of()));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (body instanceof ApiErrorResponse || body instanceof Map<?, ?>) {
            return super.handleExceptionInternal(exception, body, jsonHeaders(headers), status, request);
        }
        // Discard framework detail text: rejected values and implementation details are not public.
        ErrorCode code = switch (status.value()) {
            case 400 -> inputErrorCode(request);
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ErrorCode.NOT_ACCEPTABLE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> null;
        };
        if (status.is5xxServerError()) {
            log.error("Unexpected MVC error", exception);
            code = ErrorCode.INTERNAL_ERROR;
        }
        Map<String, Object> envelope = code != null ? envelope(code)
                : Map.of("code", "HTTP_ERROR", "message", "HTTP request could not be completed", "details", List.of());
        return super.handleExceptionInternal(exception, envelope, jsonHeaders(headers), status, request);
    }

    @ExceptionHandler({AccessDeniedException.class, SecurityException.class})
    public ResponseEntity<Map<String, Object>> handleAccessDenied(
            Exception exception, WebRequest request) throws Exception {
        if (exception instanceof AccessDeniedException && !isAdaptiveOrProbeRequest(request)) {
            throw exception;
        }
        return response(ErrorCode.CLASS_ACCESS_DENIED);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> handleDataIntegrity(
            DataIntegrityViolationException exception, WebRequest request) {
        log.warn("Database integrity conflict", exception);
        if (hasConstraint(exception, "uq_class_class_code")) {
            return response(HttpStatus.CONFLICT, "CLASS_CODE_ALREADY_EXISTS",
                    "The class code is already in use.", List.of());
        }
        if (hasConstraint(exception, "uq_skill_topics_class_name")) {
            return response(ErrorCode.SKILL_TOPIC_NAME_DUPLICATE);
        }
        if (isAdaptiveOrProbeRequest(request)) {
            return response(ErrorCode.DATA_CONFLICT);
        }
        return response(HttpStatus.CONFLICT, "RESOURCE_CONFLICT",
                "The request conflicts with existing data.", List.of());
    }

    // A direct Hibernate constraint exception must not be mistaken for Bean Validation.
    @ExceptionHandler(org.hibernate.exception.ConstraintViolationException.class)
    public ResponseEntity<?> handleHibernateConstraint(
            org.hibernate.exception.ConstraintViolationException exception, WebRequest request) {
        return handleDataIntegrity(new DataIntegrityViolationException("Database constraint conflict", exception), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(
            Exception exception, WebRequest request) throws Exception {
        if (isAdaptiveOrProbeRequest(request)) {
            log.error("Unexpected application error", exception);
            return response(ErrorCode.INTERNAL_ERROR);
        }
        throw exception;
    }

    private boolean isAdaptiveOrProbeRequest(WebRequest request) {
        String path = requestPath(request);
        if (path == null) {
            return false;
        }
        if (path.startsWith("/test/security")) {
            return false;
        }
        return path.startsWith("/test/")
                || path.startsWith("/api/v1/skill-topics")
                || path.matches("/api/v1/classes/[^/]+/skill-topics(?:/.*)?")
                || path.matches("/api/v1/lessons/[^/]+/skill-topics(?:/.*)?")
                || path.matches("/api/v1/activities/[^/]+/skill-topics(?:/.*)?")
                || path.matches("/api/v1/questions/[^/]+/skill-topics(?:/.*)?");
    }

    private ErrorCode inputErrorCode(WebRequest request) {
        // Limit the new validation contract to the existing SkillTopic PATCH operation.
        // Other MVC endpoints keep their established INVALID_REQUEST code.
        if (request instanceof ServletWebRequest servlet) {
            var http = servlet.getRequest();
            String path = requestPath(request);
            if ("PATCH".equals(http.getMethod()) && path != null && path.matches("/api/v1/skill-topics/[^/]+")) {
                return ErrorCode.VALIDATION_ERROR;
            }
        }
        return ErrorCode.INVALID_REQUEST;
    }

    private String requestPath(WebRequest request) {
        if (request instanceof ServletWebRequest servlet) {
            var http = servlet.getRequest();
            String contextPath = http.getContextPath() == null ? "" : http.getContextPath();
            String uri = http.getRequestURI();
            return uri != null && uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : uri;
        }
        return null;
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

    private HttpHeaders jsonHeaders(HttpHeaders headers) {
        HttpHeaders responseHeaders = new HttpHeaders();
        if (headers != null) {
            responseHeaders.putAll(headers);
        }
        // Error envelopes are JSON even when the request's Accept header caused a 406.
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        return responseHeaders;
    }

    private ResponseEntity<Map<String, Object>> response(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).contentType(MediaType.APPLICATION_JSON).body(envelope(code));
    }

    private Map<String, Object> envelope(ErrorCode code) {
        return Map.of("code", code.name(), "message", code.getMessage(), "details", List.of());
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status, String code, String message, List<?> details
    ) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(code, message, details));
    }

    public record ApiErrorResponse(String code, String message, List<?> details) {
    }
}

