package vn.edu.hcmut.co3103.lms.exception;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.edu.hcmut.co3103.lms.controller.admin.AdminUserController;

import java.util.List;

@RestControllerAdvice(assignableTypes = AdminUserController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminUserExceptionHandler {

    // Unexpected access/audit persistence failures are server errors in the admin contract.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<GlobalExceptionHandler.ApiErrorResponse> handlePersistenceFailure(
            DataIntegrityViolationException exception
    ) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                .body(new GlobalExceptionHandler.ApiErrorResponse("INTERNAL_SERVER_ERROR",
                        "An unexpected server error occurred.", List.of()));
    }
}
