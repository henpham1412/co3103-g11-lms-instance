package vn.edu.hcmut.co3103.lms.service.assessment;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.response.QuizConfigurationResponse;
import vn.edu.hcmut.co3103.lms.entity.Quiz;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizRepository;

import java.util.UUID;

@Service
public class QuizService {
    private final QuizRepository quizRepository;
    private final QuizConfigurationAccessService accessService;

    public QuizService(QuizRepository quizRepository, QuizConfigurationAccessService accessService) {
        this.quizRepository = quizRepository;
        this.accessService = accessService;
    }

    @Transactional(readOnly = true)
    public QuizConfigurationResponse getQuizConfiguration(UUID activityId, UUID userId) {
        Quiz quiz = quizRepository.findByActivity_Id(activityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "QUIZ_NOT_FOUND"));
        accessService.requireCanView(quiz.getActivity(), userId);
        return new QuizConfigurationResponse(quiz.getId(), quiz.getTimeLimitMinutes(),
                quiz.getAttemptsAllowed(), quiz.getGradingMethod(), quiz.getOpeningTime(),
                quiz.getDeadline(), quiz.getRequiredScore());
    }
}
