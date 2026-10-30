package vn.edu.hcmut.co3103.lms.service.assessment;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.request.QuizSettingsUpdateRequest;
import vn.edu.hcmut.co3103.lms.dto.response.QuizConfigurationResponse;
import vn.edu.hcmut.co3103.lms.entity.Quiz;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizRepository;

import java.util.UUID;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

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

    @Transactional
    public QuizConfigurationResponse updateQuizSettings(
            UUID activityId,
            UUID userId,
            QuizSettingsUpdateRequest request
    ) {
        Quiz quiz = quizRepository.findByActivity_Id(activityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "QUIZ_NOT_FOUND"));
        accessService.requireOwningTutor(quiz.getActivity(), userId);
        validateUpdate(request, quiz);

        if (request.isTimeLimitMinutesProvided()) quiz.setTimeLimitMinutes(request.getTimeLimitMinutes());
        if (request.isAttemptsAllowedProvided()) quiz.setAttemptsAllowed(request.getAttemptsAllowed());
        if (request.isGradingMethodProvided()) quiz.setGradingMethod(request.getGradingMethod());
        if (request.isOpeningTimeProvided()) quiz.setOpeningTime(request.getOpeningTime());
        if (request.isDeadlineProvided()) quiz.setDeadline(request.getDeadline());
        if (request.isRequiredScoreProvided()) quiz.setRequiredScore(request.getRequiredScore());

        return new QuizConfigurationResponse(quiz.getId(), quiz.getTimeLimitMinutes(),
                quiz.getAttemptsAllowed(), quiz.getGradingMethod(), quiz.getOpeningTime(),
                quiz.getDeadline(), quiz.getRequiredScore());
    }

    private void validateUpdate(QuizSettingsUpdateRequest request, Quiz quiz) {
        if (request == null || !request.hasAnyField()) invalidRequest();
        Integer timeLimit = request.isTimeLimitMinutesProvided()
                ? request.getTimeLimitMinutes() : quiz.getTimeLimitMinutes();
        Integer attempts = request.isAttemptsAllowedProvided()
                ? request.getAttemptsAllowed() : quiz.getAttemptsAllowed();
        String gradingMethod = request.isGradingMethodProvided()
                ? request.getGradingMethod() : quiz.getGradingMethod();
        OffsetDateTime opening = request.isOpeningTimeProvided()
                ? request.getOpeningTime() : quiz.getOpeningTime();
        OffsetDateTime deadline = request.isDeadlineProvided()
                ? request.getDeadline() : quiz.getDeadline();
        BigDecimal requiredScore = request.isRequiredScoreProvided()
                ? request.getRequiredScore() : quiz.getRequiredScore();

        if (timeLimit == null || timeLimit <= 0
                || attempts == null || attempts < 1
                || gradingMethod == null
                || !Set.of("HIGHEST", "AVERAGE", "LAST").contains(gradingMethod)
                || deadline == null
                || (opening != null && !opening.isBefore(deadline))
                || (requiredScore != null && (requiredScore.compareTo(BigDecimal.ZERO) < 0
                || requiredScore.compareTo(BigDecimal.valueOf(100)) > 0))) {
            invalidRequest();
        }
    }

    private void invalidRequest() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
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
