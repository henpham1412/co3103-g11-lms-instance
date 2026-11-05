package vn.edu.hcmut.co3103.lms.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

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

    // Jakarta Bean Validation is a caller-input error, not a database constraint failure.
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleBeanValidation(
            ConstraintViolationException exception, WebRequest request) {
        return response(inputErrorCode(request));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
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
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        // Error envelopes are JSON even when the request's Accept header caused a 406.
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        return super.handleExceptionInternal(exception, envelope, responseHeaders, status, request);
    }

    @ExceptionHandler({AccessDeniedException.class, SecurityException.class})
    public ResponseEntity<Map<String, Object>> handleAccessDenied(Exception exception) {
        return response(ErrorCode.CLASS_ACCESS_DENIED);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException exception) {
        log.warn("Database integrity conflict", exception);
        ErrorCode code = ErrorCode.DATA_CONFLICT;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint
                    && "uq_skill_topics_class_name".equals(constraint.getConstraintName())) {
                code = ErrorCode.SKILL_TOPIC_NAME_DUPLICATE;
                break;
            }
        }
        return response(code);
    }

    // A direct Hibernate constraint exception must not be mistaken for Bean Validation.
    @ExceptionHandler(org.hibernate.exception.ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleHibernateConstraint(
            org.hibernate.exception.ConstraintViolationException exception) {
        return handleDataIntegrity(new DataIntegrityViolationException("Database constraint conflict", exception));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception exception) {
        log.error("Unexpected application error", exception);
        return response(ErrorCode.INTERNAL_ERROR);
    }

    private ErrorCode inputErrorCode(WebRequest request) {
        // Limit the new validation contract to the existing SkillTopic PATCH operation.
        // Other MVC endpoints keep their established INVALID_REQUEST code.
        if (request instanceof ServletWebRequest servlet) {
            var http = servlet.getRequest();
            String path = http.getRequestURI().substring(http.getContextPath().length());
            if ("PATCH".equals(http.getMethod()) && path.matches("/api/v1/skill-topics/[^/]+")) {
                return ErrorCode.VALIDATION_ERROR;
            }
        }
        return ErrorCode.INVALID_REQUEST;
    }

    private ResponseEntity<Map<String, Object>> response(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).contentType(MediaType.APPLICATION_JSON).body(envelope(code));
    }

    private Map<String, Object> envelope(ErrorCode code) {
        return Map.of("code", code.name(), "message", code.getMessage(), "details", List.of());
    }
}
