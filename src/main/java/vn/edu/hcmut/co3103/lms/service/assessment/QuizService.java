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

    /*
     * Contract-based alternative once the teammate's proposed Learning Content
     * service is implemented. Their getActivity(activityId, userId) is the
     * applicable function here: it should resolve the Activity and apply the
     * common Tutor ownership / Student membership and published-path checks.
     * QuizService then owns only assessment configuration lookup and mapping.
     *
     * public QuizConfigurationResponse getQuizConfiguration(UUID activityId, UUID userId) {
     *     Activity activity = activityService.getActivity(activityId, userId);
     *     Quiz quiz = quizRepository.findByActivity_Id(activity.getId())
     *             .orElseThrow(() -> new ResponseStatusException(
     *                     HttpStatus.NOT_FOUND, "QUIZ_NOT_FOUND"));
     *     return new QuizConfigurationResponse(
     *             quiz.getId(), quiz.getTimeLimitMinutes(), quiz.getAttemptsAllowed(),
     *             quiz.getGradingMethod(), quiz.getOpeningTime(), quiz.getDeadline(),
     *             quiz.getRequiredScore());
     * }
     *
     * This endpoint intentionally returns quiz settings only, never questions/options.
     */
}
