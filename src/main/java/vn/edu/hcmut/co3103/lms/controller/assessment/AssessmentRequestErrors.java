package vn.edu.hcmut.co3103.lms.controller.assessment;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.List;
import java.util.Map;

/** Keep question input errors scoped to the documented assessment endpoints. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {QuestionController.class, QuizController.class})
public class AssessmentRequestErrors {
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> invalidBody(HttpServletRequest request) {
        String code = request.getRequestURI().endsWith("/quiz") ? "VALIDATION_ERROR" : "INVALID_QUESTION";
        return ResponseEntity.badRequest().body(Map.of("code", code,
                "message", "The request contains invalid or unsupported values.", "details", List.of()));
    }
}
