package vn.edu.hcmut.co3103.lms.controller.assessment;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.hcmut.co3103.lms.dto.response.QuizConfigurationResponse;
import vn.edu.hcmut.co3103.lms.dto.request.QuizSettingsUpdateRequest;
import vn.edu.hcmut.co3103.lms.service.assessment.QuizService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/activities")
public class QuizController {
    private final QuizService quizService;

    public QuizController(QuizService quizService) {
        this.quizService = quizService;
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
}

