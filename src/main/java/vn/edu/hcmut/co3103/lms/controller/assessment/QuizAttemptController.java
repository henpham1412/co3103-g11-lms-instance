package vn.edu.hcmut.co3103.lms.controller.assessment;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.hcmut.co3103.lms.dto.response.QuizAttemptStartResponse;
import vn.edu.hcmut.co3103.lms.service.assessment.QuizAttemptService;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/quizzes")
public class QuizAttemptController {
    private final QuizAttemptService attemptService;

    @PostMapping("/{quizId}/attempts")
    public ResponseEntity<QuizAttemptStartResponse> startOrResume(
            @PathVariable UUID quizId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        QuizAttemptService.StartResult result = attemptService.startOrResume(
                quizId, UUID.fromString(jwt.getSubject()));
        return ResponseEntity.status(result.status()).body(result.body());
    }
}

