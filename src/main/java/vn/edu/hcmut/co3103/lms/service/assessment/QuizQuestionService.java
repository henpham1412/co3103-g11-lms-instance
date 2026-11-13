package vn.edu.hcmut.co3103.lms.service.assessment;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.request.QuestionCreateRequest;
import vn.edu.hcmut.co3103.lms.dto.request.QuestionUpdateRequest;
import vn.edu.hcmut.co3103.lms.dto.response.QuestionResponse;
import vn.edu.hcmut.co3103.lms.entity.Question;
import vn.edu.hcmut.co3103.lms.entity.QuestionOption;
import vn.edu.hcmut.co3103.lms.entity.Quiz;
import vn.edu.hcmut.co3103.lms.repository.adaptive.QuestionSkillTopicRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionOptionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizAttemptRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizRepository;
import vn.edu.hcmut.co3103.lms.service.adaptive.SkillTopicAssociationService;
import vn.edu.hcmut.co3103.lms.service.adaptive.integration.ClassAccessAdapter;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class QuizQuestionService {
    private final AssessmentLocks locks;
    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final QuizAttemptRepository attemptRepository;
    private final QuizConfigurationAccessService accessService;
    private final QuizGradingService gradingService;
    private final SkillTopicAssociationService skillTopicAssociationService;
    private final QuestionSkillTopicRepository questionSkillTopicRepository;

    public QuizQuestionService(AssessmentLocks locks,
                               QuizRepository quizRepository,
                               QuestionRepository questionRepository,
                               QuestionOptionRepository optionRepository,
                               QuizAttemptRepository attemptRepository,
                               QuizConfigurationAccessService accessService,
                               QuizGradingService gradingService) {
        this(locks, quizRepository, questionRepository, optionRepository,
                attemptRepository, accessService, gradingService, null, null);
    }

    @Autowired
    public QuizQuestionService(AssessmentLocks locks,
                               QuizRepository quizRepository,
                               QuestionRepository questionRepository,
                               QuestionOptionRepository optionRepository,
                               QuizAttemptRepository attemptRepository,
                               QuizConfigurationAccessService accessService,
                               QuizGradingService gradingService,
                               SkillTopicAssociationService skillTopicAssociationService,
                               QuestionSkillTopicRepository questionSkillTopicRepository) {
        this.locks = locks;
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.attemptRepository = attemptRepository;
        this.accessService = accessService;
        this.gradingService = gradingService;
        this.skillTopicAssociationService = skillTopicAssociationService;
        this.questionSkillTopicRepository = questionSkillTopicRepository;
    }

    @Transactional
    public QuestionResponse addQuestion(UUID activityId, UUID userId, QuestionCreateRequest request) {
        locks.lockActivity(activityId);
        Quiz quiz = quizRepository.findLockedByActivity_ActivityId(activityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "QUIZ_NOT_FOUND"));
        accessService.requireOwningTutor(quiz.getActivity(), userId);
        if (attemptRepository.existsByQuiz_Id(quiz.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "QUESTION_EDIT_LOCKED");
        }
        BigDecimal pointsDeducted = request == null || request.pointsDeducted() == null
                ? BigDecimal.ZERO : request.pointsDeducted();
        validateQuestion(request, pointsDeducted);
        if (questionRepository.existsByQuiz_IdAndOrderIndex(quiz.getId(), request.orderIndex())) {
            throw invalidQuestion();
        }

        Question question = new Question();
        question.setQuiz(quiz);
        question.setQuestionType(request.questionType());
        question.setContent(request.content().trim());
        question.setPointsAwarded(request.pointsAwarded());
        question.setPointsDeducted(pointsDeducted);
        question.setOrderIndex(request.orderIndex());
        Question savedQuestion = questionRepository.save(question);

        List<QuestionResponse.Option> options = request.options().stream().map(option -> {
            QuestionOption entity = new QuestionOption();
            entity.setQuestion(savedQuestion);
            entity.setContent(option.content().trim());
            entity.setIsCorrect(option.isCorrect());
            entity.setOrderIndex(option.orderIndex());
            QuestionOption saved = optionRepository.save(entity);
            return new QuestionResponse.Option(saved.getId(), saved.getContent(),
                    saved.getIsCorrect(), saved.getOrderIndex());
        }).toList();

        if (request.skillTopicIds() != null && skillTopicAssociationService != null) {
            questionRepository.flush();
            ClassAccessAdapter.withActor(userId, () ->
                    skillTopicAssociationService.replaceQuestionTopics(savedQuestion.getId(), request.skillTopicIds()));
        }

        return new QuestionResponse(savedQuestion.getId(), savedQuestion.getQuestionType(),
                savedQuestion.getContent(), savedQuestion.getPointsAwarded(),
                savedQuestion.getPointsDeducted(), savedQuestion.getOrderIndex(), options);
    }

    @Transactional
    public QuestionResponse updateQuestion(UUID questionId, UUID userId, QuestionUpdateRequest request) {
        UUID quizId = questionRepository.findQuizIdByQuestionId(questionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "QUESTION_NOT_FOUND"));
        locks.lockQuiz(quizId);
        Quiz quiz = quizRepository.findLockedById(quizId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "QUIZ_NOT_FOUND"));
        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "QUESTION_NOT_FOUND"));
        accessService.requireOwningTutor(quiz.getActivity(), userId);
        if (request == null || !hasAnyField(request)) throw invalidQuestion();

        boolean hasAttempts = attemptRepository.existsByQuiz_Id(quiz.getId());
        List<QuestionOption> existingOptions = optionRepository.findByQuestion_IdOrderByOrderIndex(questionId);
        List<QuestionUpdateRequest.Option> requestedOptions = request.optionsProvided()
                ? request.options() : existingOptions.stream().map(option -> new QuestionUpdateRequest.Option(
                        option.getId(), option.getContent(), option.getIsCorrect(), option.getOrderIndex())).toList();

        String newType = request.questionTypeProvided() ? request.questionType() : question.getQuestionType();
        String newContent = request.contentProvided() ? request.content() : question.getContent();
        BigDecimal newPointsAwarded = request.pointsAwardedProvided()
                ? request.pointsAwarded() : question.getPointsAwarded();
        BigDecimal newPointsDeducted = request.pointsDeductedProvided()
                ? request.pointsDeducted() : question.getPointsDeducted();
        Integer newOrderIndex = request.orderIndexProvided() ? request.orderIndex() : question.getOrderIndex();

        validateQuestion(newType, newContent, newPointsAwarded, newPointsDeducted,
                newOrderIndex, requestedOptions, questionId, quiz.getId());
        if (request.skillTopicIdsProvided() && request.skillTopicIds() == null) {
            throw invalidQuestion();
        }

        if (hasAttempts && !newType.equals(question.getQuestionType())) throw lockedQuestion();
        Set<UUID> suppliedExistingIds = requestedOptions.stream().map(QuestionUpdateRequest.Option::optionId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> existingIds = existingOptions.stream().map(QuestionOption::getId).collect(Collectors.toSet());
        if (!existingIds.containsAll(suppliedExistingIds)) throw invalidQuestion();
        if (hasAttempts && !suppliedExistingIds.containsAll(existingIds)) throw lockedQuestion();
        if (questionRepository.existsByQuiz_IdAndOrderIndexAndIdNot(quiz.getId(), newOrderIndex, questionId)) {
            throw invalidQuestion();
        }

        boolean gradingChanged = !newPointsAwarded.equals(question.getPointsAwarded())
                || !newPointsDeducted.equals(question.getPointsDeducted())
                || optionsAffectGrading(requestedOptions, existingOptions);

        if (request.optionsProvided()) persistOptions(question, existingOptions, requestedOptions);
        question.setQuestionType(newType);
        question.setContent(newContent.trim());
        question.setPointsAwarded(newPointsAwarded);
        question.setPointsDeducted(newPointsDeducted);
        question.setOrderIndex(newOrderIndex);

        Set<UUID> changedTopics = new HashSet<>();
        if (request.skillTopicIdsProvided() && skillTopicAssociationService != null) {
            questionRepository.flush();
            var change = ClassAccessAdapter.withActor(userId, () ->
                    skillTopicAssociationService.replaceQuestionTopics(questionId, request.skillTopicIds()));
            changedTopics.addAll(change.previousTopicIds());
            changedTopics.addAll(change.currentTopicIds());
        }

        if (hasAttempts && gradingChanged) {
            if (changedTopics.isEmpty()) gradingService.recalculateSubmittedAttempts(quiz);
            else gradingService.recalculateSubmittedAttempts(quiz, changedTopics);
        } else if (request.skillTopicIdsProvided() && skillTopicAssociationService != null) {
            gradingService.synchronizeMastery(quiz, changedTopics);
        }
        return toResponse(question, optionRepository.findByQuestion_IdOrderByOrderIndex(questionId));
    }

    // note: no regrade happens if a question is deleted; it cannot happen had any attempts been submitted.
    @Transactional
    public void deleteQuestion(UUID questionId, UUID userId) {
        UUID quizId = questionRepository.findQuizIdByQuestionId(questionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "QUESTION_NOT_FOUND"));
        locks.lockQuiz(quizId);
        Quiz quiz = quizRepository.findLockedById(quizId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "QUIZ_NOT_FOUND"));
        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "QUESTION_NOT_FOUND"));
        accessService.requireOwningTutor(quiz.getActivity(), userId);
        if (attemptRepository.existsByQuiz_Id(quiz.getId())) {
            throw lockedQuestion();
        }

        if (quiz.getActivity().getStatus() == vn.edu.hcmut.co3103.lms.entity.Chapter.VisibilityStatus.PUBLISHED
                && questionRepository.findByQuiz_IdOrderByOrderIndex(quiz.getId()).size() <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ASSESSMENT_CONFIGURATION_MISMATCH");
        }
        if (questionSkillTopicRepository != null) {
            var links = questionSkillTopicRepository.findAllById_QuestionId(questionId);
            if (!links.isEmpty()) {
                questionSkillTopicRepository.deleteAll(links);
                questionSkillTopicRepository.flush();
            }
        }
        optionRepository.deleteByQuestion_Id(questionId);
        optionRepository.flush();
        questionRepository.delete(question);
        questionRepository.flush();
        if (skillTopicAssociationService != null && quiz.getActivity() != null
                && quiz.getActivity().getActivityId() != null) {
            UUID activityId = quiz.getActivity().getActivityId();
            ClassAccessAdapter.withActor(userId, () ->
                    skillTopicAssociationService.synchronizeQuizTopics(activityId));
        }
    }

    private boolean hasAnyField(QuestionUpdateRequest request) {
        return request.questionTypeProvided() || request.contentProvided() || request.pointsAwardedProvided()
                || request.pointsDeductedProvided() || request.orderIndexProvided() || request.optionsProvided()
                || request.skillTopicIdsProvided();
    }

    private boolean optionsAffectGrading(List<QuestionUpdateRequest.Option> requested,
                                         List<QuestionOption> existing) {
        Map<UUID, QuestionOption> byId = existing.stream().collect(Collectors.toMap(
                QuestionOption::getId, Function.identity()));
        for (QuestionUpdateRequest.Option option : requested) {
            if (option.optionId() == null) {
                if (Boolean.TRUE.equals(option.isCorrect())) return true;
                continue;
            }
            QuestionOption old = byId.get(option.optionId());
            if (old == null || !old.getIsCorrect().equals(option.isCorrect())) return true;
        }
        return false;
    }

    private void persistOptions(Question question, List<QuestionOption> existing,
                                List<QuestionUpdateRequest.Option> requested) {
        Map<UUID, QuestionOption> byId = existing.stream().collect(Collectors.toMap(
                QuestionOption::getId, Function.identity()));
        Set<UUID> keepIds = requested.stream().map(QuestionUpdateRequest.Option::optionId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        List<QuestionOption> removed = existing.stream().filter(option -> !keepIds.contains(option.getId())).toList();
        List<QuestionOption> surviving = existing.stream().filter(option -> keepIds.contains(option.getId())).toList();
        if (!removed.isEmpty()) optionRepository.deleteAll(removed);
        optionRepository.flush();

        int maxRequestedOrder = requested.stream().map(QuestionUpdateRequest.Option::orderIndex)
                .max(Integer::compareTo).orElse(0);
        long maxExistingOrder = surviving.stream().map(QuestionOption::getOrderIndex)
                .max(Integer::compareTo).orElse(0);
        long offset = Math.max(maxRequestedOrder, maxExistingOrder) + surviving.size() + 1L;
        if (surviving.stream().anyMatch(option -> (long) option.getOrderIndex() + offset > Integer.MAX_VALUE)) {
            throw invalidQuestion();
        }
        for (QuestionOption option : surviving) option.setOrderIndex(option.getOrderIndex() + (int) offset);
        optionRepository.saveAll(surviving);
        optionRepository.flush();

        List<QuestionOption> finalOptions = new ArrayList<>(requested.size());
        for (QuestionUpdateRequest.Option item : requested) {
            QuestionOption option = item.optionId() == null ? new QuestionOption() : byId.get(item.optionId());
            option.setQuestion(question);
            option.setContent(item.content().trim());
            option.setIsCorrect(item.isCorrect());
            option.setOrderIndex(item.orderIndex());
            finalOptions.add(option);
        }
        optionRepository.saveAll(finalOptions);
    }

    private QuestionResponse toResponse(Question question, List<QuestionOption> options) {
        return new QuestionResponse(question.getId(), question.getQuestionType(), question.getContent(),
                question.getPointsAwarded(), question.getPointsDeducted(), question.getOrderIndex(),
                options.stream().map(option -> new QuestionResponse.Option(option.getId(), option.getContent(),
                        option.getIsCorrect(), option.getOrderIndex())).toList());
    }

    private static boolean representablePoints(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.stripTrailingZeros().scale() <= 2
                && value.compareTo(new BigDecimal("99999999.99")) <= 0;
    }

    private ResponseStatusException invalidQuestion() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUESTION");
    }

    private ResponseStatusException lockedQuestion() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "QUESTION_EDIT_LOCKED");
    }

    void validateInitialQuestion(QuestionCreateRequest request) {
        validateQuestion(request, request == null || request.pointsDeducted() == null
                ? BigDecimal.ZERO : request.pointsDeducted());
    }

    private void validateQuestion(QuestionCreateRequest request, BigDecimal pointsDeducted) {
        if (request == null || request.options() == null) throw invalidQuestion();
        validateQuestion(request.questionType(), request.content(), request.pointsAwarded(),
                pointsDeducted, request.orderIndex(), request.options().stream()
                        .map(option -> option == null ? null : new QuestionUpdateRequest.Option(null,
                                option.content(), option.isCorrect(), option.orderIndex())).toList(), null, null);
    }

    private void validateQuestion(String type, String content, BigDecimal pointsAwarded,
                                  BigDecimal pointsDeducted, Integer orderIndex,
                                  List<QuestionUpdateRequest.Option> options,
                                  UUID questionId, UUID quizId) {
        if (type == null || !Set.of("MCQ_SINGLE", "MCQ_MULTIPLE", "TRUE_FALSE").contains(type)
                || content == null || content.isBlank()
                || !representablePoints(pointsAwarded)
                || !representablePoints(pointsDeducted)
                || orderIndex == null || orderIndex < 0 || options == null || options.isEmpty()
                || (type.startsWith("MCQ_") && options.size() < 2)
                || ("TRUE_FALSE".equals(type) && options.size() != 2)
                || (quizId != null && (questionId == null
                ? questionRepository.existsByQuiz_IdAndOrderIndex(quizId, orderIndex)
                : questionRepository.existsByQuiz_IdAndOrderIndexAndIdNot(quizId, orderIndex, questionId)))) {
            throw invalidQuestion();
        }

        long correctCount = 0;
        Set<Integer> optionOrders = new HashSet<>();
        Set<UUID> optionIds = new HashSet<>();
        for (QuestionUpdateRequest.Option option : options) {
            if (option == null || option.content() == null || option.content().isBlank()
                    || option.isCorrect() == null || option.orderIndex() == null || option.orderIndex() < 0
                    || !optionOrders.add(option.orderIndex())
                    || (option.optionId() != null && !optionIds.add(option.optionId()))) {
                throw invalidQuestion();
            }
            if (option.isCorrect()) correctCount++;
        }
        if (correctCount == 0 || (("MCQ_SINGLE".equals(type)
                || "TRUE_FALSE".equals(type)) && correctCount != 1)) {
            throw invalidQuestion();
        }
    }
}
