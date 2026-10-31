package vn.edu.hcmut.co3103.lms.controller.assessment;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.hcmut.co3103.lms.dto.response.QuizAttemptStartResponse;
import vn.edu.hcmut.co3103.lms.dto.response.QuizAttemptResultResponse;
import vn.edu.hcmut.co3103.lms.dto.request.SaveAnswerRequest;
import vn.edu.hcmut.co3103.lms.service.assessment.QuizAttemptService;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class QuizAttemptController {
    private final QuizAttemptService attemptService;

    @PostMapping("/quizzes/{quizId}/attempts")
    public ResponseEntity<QuizAttemptStartResponse> startOrResume(
            @PathVariable UUID quizId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        QuizAttemptService.StartResult result = attemptService.startOrResume(
                quizId, UUID.fromString(jwt.getSubject()));
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PutMapping("/quiz-attempts/{attemptId}/answers/{questionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveAnswer(
            @PathVariable UUID attemptId,
            @PathVariable UUID questionId,
            @RequestBody SaveAnswerRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        attemptService.saveAnswer(attemptId, questionId, UUID.fromString(jwt.getSubject()), request);
    }

    @DeleteMapping("/quiz-attempts/{attemptId}/answers/{questionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearAnswer(
            @PathVariable UUID attemptId,
            @PathVariable UUID questionId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        attemptService.clearAnswer(attemptId, questionId, UUID.fromString(jwt.getSubject()));
    }

    @PostMapping("/quiz-attempts/{attemptId}/submit")
    public QuizAttemptResultResponse submit(
            @PathVariable UUID attemptId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return attemptService.submit(attemptId, UUID.fromString(jwt.getSubject()));
    }
}

