package vn.edu.hcmut.co3103.lms.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Map;

/** DOC-05 error envelope only; no feature controller or transport DTO is introduced. */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<Map<String, Object>> handleAppException(AppException exception) {
        return response(exception.getErrorCode());
    }

    // Jakarta Bean Validation is a caller-input error, not a database constraint failure.
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleBeanValidation(ConstraintViolationException exception) {
        return response(ErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> handleInvalidRequest(Exception exception) {
        return response(ErrorCode.INVALID_REQUEST);
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

    private ResponseEntity<Map<String, Object>> response(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(Map.of(
                "code", code.name(), "message", code.getMessage(), "details", List.of()));
    }
}
