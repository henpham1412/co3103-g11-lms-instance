package vn.edu.hcmut.co3103.lms.service.assessment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.entity.Activity;
import vn.edu.hcmut.co3103.lms.entity.Quiz;
import vn.edu.hcmut.co3103.lms.dto.request.QuestionCreateRequest;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ActivityRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.*;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.*;

/** LMS-4 persists Activity and calls this owner service before committing the same transaction. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
public class QuizCreationService {
    private final AssessmentLocks locks;
    private final ActivityRepository activities;
    private final QuizRepository quizzes;
    private final QuestionRepository questions;
    private final QuestionOptionRepository options;
    private final QuizQuestionService questionService;
    private final QuizConfigurationAccessService access;

    public CreatedQuiz createForActivity(UUID activityId, UUID actorId, QuizCreation command) {
        locks.lockActivity(activityId);
        Activity activity = activities.findById(activityId).orElseThrow(() -> mismatch());
        access.requireOwningTutor(activity, actorId);
        if (activity.getActivityType() != Activity.ActivityType.QUIZ
                || quizzes.findByActivity_ActivityId(activityId).isPresent()) throw mismatch();
        if (command == null || command.questions() == null || command.questions().isEmpty()) throw invalid();
        int attempts = command.attemptsAllowed() == null ? 1 : command.attemptsAllowed();
        String grading = command.gradingMethod() == null ? "HIGHEST" : command.gradingMethod();
        OffsetDateTime now = OffsetDateTime.now();
        if (command.timeLimitMinutes() == null || command.timeLimitMinutes() <= 0 || attempts < 1
                || !Set.of("HIGHEST", "AVERAGE", "LAST").contains(grading)
                || command.deadline() == null || !command.deadline().isAfter(now)
                || (command.openingTime() != null && !command.openingTime().isBefore(command.deadline()))
                || (command.requiredScore() != null && (command.requiredScore().signum() < 0
                    || command.requiredScore().compareTo(BigDecimal.valueOf(100)) > 0))) throw invalid();
        Set<Integer> positions = new HashSet<>();
        for (QuestionCreateRequest question : command.questions()) {
            questionService.validateInitialQuestion(question);
            if (!positions.add(question.orderIndex())) throw invalid();
        }
        Quiz quiz = new Quiz();
        quiz.setActivity(activity); quiz.setTimeLimitMinutes(command.timeLimitMinutes());
        quiz.setAttemptsAllowed(attempts); quiz.setGradingMethod(grading);
        quiz.setOpeningTime(command.openingTime()); quiz.setDeadline(command.deadline());
        quiz.setRequiredScore(command.requiredScore()); quiz.setCreatedAt(now); quiz.setUpdatedAt(now);
        quizzes.saveAndFlush(quiz);
        for (QuestionCreateRequest question : command.questions()) questionService.addQuestion(activityId, actorId, question);
        quizzes.flush();
        return new CreatedQuiz(quiz.getId(), activityId);
    }

    /** Caller holds the Activity publication transaction; this service never changes Activity. */
    public void requirePublishable(UUID activityId, UUID actorId) {
        locks.lockActivity(activityId);
        Quiz quiz = quizzes.findLockedByActivity_ActivityId(activityId).orElseThrow(() -> mismatch());
        access.requireOwningTutor(quiz.getActivity(), actorId);
        if (quiz.getActivity().getActivityType() != Activity.ActivityType.QUIZ) throw mismatch();
        var items = questions.findByQuiz_IdOrderByOrderIndex(quiz.getId());
        if (items.isEmpty()) throw mismatch();
        for (var q : items) {
            var choices = options.findByQuestion_IdOrderByOrderIndex(q.getId()).stream()
                    .map(o -> new QuestionCreateRequest.Option(o.getContent(), o.getIsCorrect(), o.getOrderIndex())).toList();
            questionService.validateInitialQuestion(new QuestionCreateRequest(q.getQuestionType(), q.getContent(),
                    q.getPointsAwarded(), q.getPointsDeducted(), q.getOrderIndex(), choices, null));
        }
    }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR"); }
    private static ResponseStatusException mismatch() { return new ResponseStatusException(HttpStatus.CONFLICT, "ASSESSMENT_CONFIGURATION_MISMATCH"); }
    public record CreatedQuiz(UUID quizId, UUID activityId) { }
}
