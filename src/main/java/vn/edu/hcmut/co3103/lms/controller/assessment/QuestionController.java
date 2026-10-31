package vn.edu.hcmut.co3103.lms.controller.assessment;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.hcmut.co3103.lms.dto.request.QuestionUpdateRequest;
import vn.edu.hcmut.co3103.lms.dto.response.QuestionResponse;
import vn.edu.hcmut.co3103.lms.service.assessment.QuizQuestionService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/questions")
public class QuestionController {
    private final QuizQuestionService questionService;

    public QuestionController(QuizQuestionService questionService) {
        this.questionService = questionService;
    }

    @PatchMapping("/{questionId}")
    public QuestionResponse updateQuestion(
            @PathVariable UUID questionId,
            @RequestBody QuestionUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return questionService.updateQuestion(questionId, UUID.fromString(jwt.getSubject()), request);
    }
}
