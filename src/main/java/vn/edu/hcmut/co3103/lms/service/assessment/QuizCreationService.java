package vn.edu.hcmut.co3103.lms.service.assessment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.entity.Activity;
import vn.edu.hcmut.co3103.lms.entity.Chapter.VisibilityStatus;
import vn.edu.hcmut.co3103.lms.entity.Quiz;
import vn.edu.hcmut.co3103.lms.dto.request.QuestionCreateRequest;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ActivityRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.*;
import vn.edu.hcmut.co3103.lms.repository.notification.NotificationDeliveryRepository.ContentScope;
import vn.edu.hcmut.co3103.lms.service.notification.NotificationEvents;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;

/** LMS-4 persists Activity and calls this owner service before committing the same transaction. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
public class QuizCreationService {
    private static final Set<String> QUIZ_FIELDS = Set.of(
            "time_limit_minutes", "attempts_allowed", "grading_method",
            "opening_time", "deadline", "required_score", "questions");
    private static final Set<String> QUESTION_FIELDS = Set.of(
            "question_type", "content", "points_awarded", "points_deducted",
            "order_index", "options", "skill_topic_ids");
    private static final Set<String> OPTION_FIELDS = Set.of("content", "is_correct", "order_index");

    private final AssessmentLocks locks;
    private final ActivityRepository activities;
    private final QuizRepository quizzes;
    private final QuestionRepository questions;
    private final QuestionOptionRepository options;
    private final QuizQuestionService questionService;
    private final QuizConfigurationAccessService access;
    private final NotificationEvents notifications;

    public CreatedQuiz createFromRequest(UUID activityId, UUID actorId, Object configuration) {
        if (!(configuration instanceof Map<?, ?> fields) || !QUIZ_FIELDS.containsAll(fields.keySet())) {
            throw invalid();
        }
        Integer timeLimitMinutes = strictInteger(fields.get("time_limit_minutes"), true, QuizCreationService::invalid);
        Integer attemptsAllowed = strictInteger(fields.get("attempts_allowed"), false, QuizCreationService::invalid);
        String gradingMethod = strictString(fields.get("grading_method"), false, QuizCreationService::invalid);
        OffsetDateTime openingTime = strictTimestamp(fields.get("opening_time"), false);
        OffsetDateTime deadline = strictTimestamp(fields.get("deadline"), true);
        BigDecimal requiredScore = strictDecimal(fields.get("required_score"), false, QuizCreationService::invalid);
        List<QuestionCreateRequest> initialQuestions = parseQuestions(fields.get("questions"));
        return createForActivity(activityId, actorId, new QuizCreation(
                timeLimitMinutes, attemptsAllowed, gradingMethod, openingTime, deadline, requiredScore, initialQuestions));
    }

    public CreatedQuiz createForActivity(UUID activityId, UUID actorId, QuizCreation command) {
        locks.lockActivity(activityId);
        Activity activity = activities.findById(activityId).orElseThrow(() -> mismatch());
        access.requireOwningTutor(activity, actorId);
        if (activity.getActivityType() != Activity.ActivityType.QUIZ
                || quizzes.findByActivity_ActivityId(activityId).isPresent()) throw mismatch();
        if (command == null) throw invalid();
        int attempts = command.attemptsAllowed() == null ? 1 : command.attemptsAllowed();
        String grading = command.gradingMethod() == null ? "HIGHEST" : command.gradingMethod();
        OffsetDateTime now = OffsetDateTime.now();
        if (command.timeLimitMinutes() == null || command.timeLimitMinutes() <= 0 || attempts < 1
                || !Set.of("HIGHEST", "AVERAGE", "LAST").contains(grading)
                || command.deadline() == null || !command.deadline().isAfter(now)
                || (command.openingTime() != null && !command.openingTime().isBefore(command.deadline()))
                || (command.requiredScore() != null && (command.requiredScore().signum() < 0
                    || command.requiredScore().compareTo(BigDecimal.valueOf(100)) > 0
                    || command.requiredScore().stripTrailingZeros().scale() > 2))) throw invalid();
        List<QuestionCreateRequest> initialQuestions = command.questions() == null ? List.of() : command.questions();
        if (activity.getStatus() == VisibilityStatus.PUBLISHED && initialQuestions.isEmpty()) {
            throw mismatch();
        }
        Set<Integer> positions = new HashSet<>();
        for (QuestionCreateRequest question : initialQuestions) {
            questionService.validateInitialQuestion(question);
            if (!positions.add(question.orderIndex())) throw invalid();
        }
        Quiz quiz = new Quiz();
        quiz.setActivity(activity); quiz.setTimeLimitMinutes(command.timeLimitMinutes());
        quiz.setAttemptsAllowed(attempts); quiz.setGradingMethod(grading);
        quiz.setOpeningTime(command.openingTime()); quiz.setDeadline(command.deadline());
        quiz.setRequiredScore(command.requiredScore()); quiz.setCreatedAt(now); quiz.setUpdatedAt(now);
        quizzes.saveAndFlush(quiz);
        for (QuestionCreateRequest question : initialQuestions) {
            questionService.addQuestion(activityId, actorId, question);
        }
        quizzes.flush();
        if (activity.getStatus() == VisibilityStatus.PUBLISHED) {
            notifications.contentReleased(ContentScope.ACTIVITY, activityId);
        }
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

    private List<QuestionCreateRequest> parseQuestions(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof List<?> list)) throw invalid();
        List<QuestionCreateRequest> parsed = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> q) || !QUESTION_FIELDS.containsAll(q.keySet())) {
                throw invalidQuestion();
            }
            String questionType = strictString(q.get("question_type"), true, QuizCreationService::invalidQuestion);
            String content = strictString(q.get("content"), true, QuizCreationService::invalidQuestion);
            BigDecimal pointsAwarded = strictDecimal(q.get("points_awarded"), true, QuizCreationService::invalidQuestion);
            BigDecimal pointsDeducted = strictDecimal(q.get("points_deducted"), false, QuizCreationService::invalidQuestion);
            Integer orderIndex = strictInteger(q.get("order_index"), true, QuizCreationService::invalidQuestion);
            List<QuestionCreateRequest.Option> parsedOptions = parseOptions(q.get("options"));
            List<UUID> skillTopicIds = parseSkillTopicIds(q.get("skill_topic_ids"));
            parsed.add(new QuestionCreateRequest(
                    questionType, content, pointsAwarded, pointsDeducted, orderIndex, parsedOptions, skillTopicIds));
        }
        return parsed;
    }

    private List<QuestionCreateRequest.Option> parseOptions(Object raw) {
        if (!(raw instanceof List<?> list)) throw invalidQuestion();
        List<QuestionCreateRequest.Option> parsed = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> opt) || !OPTION_FIELDS.containsAll(opt.keySet())) {
                throw invalidQuestion();
            }
            String content = strictString(opt.get("content"), true, QuizCreationService::invalidQuestion);
            Object correctRaw = opt.get("is_correct");
            if (!(correctRaw instanceof Boolean isCorrect)) throw invalidQuestion();
            Integer orderIndex = strictInteger(opt.get("order_index"), true, QuizCreationService::invalidQuestion);
            parsed.add(new QuestionCreateRequest.Option(content, isCorrect, orderIndex));
        }
        return parsed;
    }

    private List<UUID> parseSkillTopicIds(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof List<?> list)) throw invalidQuestion();
        List<UUID> ids = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String text)) throw invalidQuestion();
            try { ids.add(UUID.fromString(text)); }
            catch (IllegalArgumentException exception) { throw invalidQuestion(); }
        }
        return ids;
    }

    private static Integer strictInteger(Object value, boolean required, Supplier<ResponseStatusException> error) {
        if (value == null) {
            if (required) throw error.get();
            return null;
        }
        if (value instanceof Integer integer) return integer;
        throw error.get();
    }

    private static String strictString(Object value, boolean required, Supplier<ResponseStatusException> error) {
        if (value == null) {
            if (required) throw error.get();
            return null;
        }
        if (value instanceof String text) return text;
        throw error.get();
    }

    private static OffsetDateTime strictTimestamp(Object value, boolean required) {
        if (value == null) {
            if (required) throw invalid();
            return null;
        }
        if (!(value instanceof String text)) throw invalid();
        try { return OffsetDateTime.parse(text); }
        catch (DateTimeParseException exception) { throw invalid(); }
    }

    private static BigDecimal strictDecimal(Object value, boolean required, Supplier<ResponseStatusException> error) {
        if (value == null) {
            if (required) throw error.get();
            return null;
        }
        if (!(value instanceof Number number)) throw error.get();
        if (number instanceof Double d && !Double.isFinite(d)) throw error.get();
        if (number instanceof Float f && !Float.isFinite(f)) throw error.get();
        try { return new BigDecimal(number.toString()); }
        catch (NumberFormatException exception) { throw error.get(); }
    }

    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR"); }
    private static ResponseStatusException invalidQuestion() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUESTION"); }
    private static ResponseStatusException mismatch() { return new ResponseStatusException(HttpStatus.CONFLICT, "ASSESSMENT_CONFIGURATION_MISMATCH"); }
    public record CreatedQuiz(UUID quizId, UUID activityId) { }
}
