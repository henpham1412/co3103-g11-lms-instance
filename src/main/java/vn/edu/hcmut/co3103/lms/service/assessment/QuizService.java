package vn.edu.hcmut.co3103.lms.service.assessment;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.request.QuizSettingsUpdateRequest;
import vn.edu.hcmut.co3103.lms.dto.response.QuestionResponse;
import vn.edu.hcmut.co3103.lms.dto.response.QuizConfigurationResponse;
import vn.edu.hcmut.co3103.lms.entity.Question;
import vn.edu.hcmut.co3103.lms.entity.QuestionOption;
import vn.edu.hcmut.co3103.lms.entity.Quiz;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionOptionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class QuizService {
    private final AssessmentLocks locks;
    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final QuizConfigurationAccessService accessService;

    @Transactional(readOnly = true)
    public QuizConfigurationResponse getQuizConfiguration(UUID activityId, UUID userId) {
        Quiz quiz = quizRepository.findByActivity_ActivityId(activityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "QUIZ_NOT_FOUND"));
        boolean isTutor = accessService.requireCanViewAsTutor(quiz.getActivity(), userId);
        List<QuestionResponse> tutorQuestions = isTutor ? loadTutorQuestions(quiz.getId()) : null;
        return new QuizConfigurationResponse(quiz.getId(), quiz.getActivity().getActivityId(),
                quiz.getTimeLimitMinutes(), quiz.getAttemptsAllowed(), quiz.getGradingMethod(),
                quiz.getOpeningTime(), quiz.getDeadline(), quiz.getRequiredScore(), tutorQuestions);
    }

    private List<QuestionResponse> loadTutorQuestions(UUID quizId) {
        List<Question> quizQuestions = questionRepository.findByQuiz_IdOrderByOrderIndex(quizId);
        if (quizQuestions.isEmpty()) {
            return List.of();
        }
        List<UUID> questionIds = quizQuestions.stream().map(Question::getId).toList();
        Map<UUID, List<QuestionResponse.Option>> optionsByQuestion = optionRepository
                .findByQuestion_IdInOrderByQuestion_IdAscOrderIndexAsc(questionIds).stream()
                .collect(Collectors.groupingBy(
                        option -> option.getQuestion().getId(),
                        Collectors.mapping(
                                option -> new QuestionResponse.Option(option.getId(), option.getContent(),
                                        option.getIsCorrect(), option.getOrderIndex()),
                                Collectors.toList())));
        return quizQuestions.stream()
                .map(question -> new QuestionResponse(
                        question.getId(), question.getQuestionType(), question.getContent(),
                        question.getPointsAwarded(), question.getPointsDeducted(), question.getOrderIndex(),
                        optionsByQuestion.getOrDefault(question.getId(), List.of())))
                .toList();
    }

    @Transactional
    public QuizConfigurationResponse updateQuizSettings(
            UUID activityId,
            UUID userId,
            QuizSettingsUpdateRequest request
    ) {
        locks.lockActivity(activityId);
        Quiz quiz = quizRepository.findLockedByActivity_ActivityId(activityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "QUIZ_NOT_FOUND"));
        accessService.requireOwningTutor(quiz.getActivity(), userId);
        validateUpdate(request, quiz);

        if (request.isTimeLimitMinutesProvided()) quiz.setTimeLimitMinutes(request.getTimeLimitMinutes());
        if (request.isAttemptsAllowedProvided()) quiz.setAttemptsAllowed(request.getAttemptsAllowed());
        if (request.isGradingMethodProvided()) quiz.setGradingMethod(request.getGradingMethod());
        if (request.isOpeningTimeProvided()) quiz.setOpeningTime(request.getOpeningTime());
        if (request.isDeadlineProvided()) quiz.setDeadline(request.getDeadline());
        if (request.isRequiredScoreProvided()) quiz.setRequiredScore(request.getRequiredScore());

        return new QuizConfigurationResponse(quiz.getId(), quiz.getActivity().getActivityId(),
                quiz.getTimeLimitMinutes(), quiz.getAttemptsAllowed(), quiz.getGradingMethod(),
                quiz.getOpeningTime(), quiz.getDeadline(), quiz.getRequiredScore());
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
                || (request.isDeadlineProvided() && !deadline.isAfter(OffsetDateTime.now()))
                || (opening != null && !opening.isBefore(deadline))
                || (requiredScore != null && (requiredScore.compareTo(BigDecimal.ZERO) < 0
                || requiredScore.compareTo(BigDecimal.valueOf(100)) > 0))) {
            invalidRequest();
        }
    }

    private void invalidRequest() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
    }

}
