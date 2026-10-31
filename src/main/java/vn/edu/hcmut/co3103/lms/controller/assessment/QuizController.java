package vn.edu.hcmut.co3103.lms.controller.assessment;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.hcmut.co3103.lms.dto.response.QuizConfigurationResponse;
import vn.edu.hcmut.co3103.lms.dto.request.QuizSettingsUpdateRequest;
import vn.edu.hcmut.co3103.lms.dto.request.QuestionCreateRequest;
import vn.edu.hcmut.co3103.lms.dto.response.QuestionResponse;
import vn.edu.hcmut.co3103.lms.service.assessment.QuizService;
import vn.edu.hcmut.co3103.lms.service.assessment.QuizQuestionService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/activities")
public class QuizController {
    private final QuizService quizService;
    private final QuizQuestionService quizQuestionService;

    public QuizController(QuizService quizService, QuizQuestionService quizQuestionService) {
        this.quizService = quizService;
        this.quizQuestionService = quizQuestionService;
    }

    @GetMapping("/{activityId}/quiz")
    public QuizConfigurationResponse getQuizConfiguration(
            @PathVariable UUID activityId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return quizService.getQuizConfiguration(activityId, UUID.fromString(jwt.getSubject()));
    }

    @PatchMapping("/{activityId}/quiz")
    public QuizConfigurationResponse updateQuizSettings(
            @PathVariable UUID activityId,
            @RequestBody QuizSettingsUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return quizService.updateQuizSettings(activityId, UUID.fromString(jwt.getSubject()), request);
    }

    @PostMapping("/{activityId}/questions")
    @ResponseStatus(HttpStatus.CREATED)
    public QuestionResponse addQuestion(
            @PathVariable UUID activityId,
            @RequestBody QuestionCreateRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return quizQuestionService.addQuestion(activityId, UUID.fromString(jwt.getSubject()), request);
    }
}

