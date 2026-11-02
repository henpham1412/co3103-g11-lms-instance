package vn.edu.hcmut.co3103.lms.service.assessment;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.response.QuizAttemptStartResponse;
import vn.edu.hcmut.co3103.lms.dto.request.SaveAnswerRequest;
import vn.edu.hcmut.co3103.lms.dto.response.QuizAttemptResultResponse;
import vn.edu.hcmut.co3103.lms.dto.response.QuizAttemptStateResponse;
import vn.edu.hcmut.co3103.lms.dto.response.QuizAttemptListResponse;
import vn.edu.hcmut.co3103.lms.entity.Activity;
import vn.edu.hcmut.co3103.lms.entity.AttemptAnswer;
import vn.edu.hcmut.co3103.lms.entity.AttemptAnswerOption;
import vn.edu.hcmut.co3103.lms.entity.AttemptAnswerOptionId;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.Question;
import vn.edu.hcmut.co3103.lms.entity.QuestionOption;
import vn.edu.hcmut.co3103.lms.entity.Quiz;
import vn.edu.hcmut.co3103.lms.entity.QuizAttempt;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionOptionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.AttemptAnswerRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.AttemptAnswerOptionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizAttemptRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizRepository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QuizAttemptService {
    private final QuizRepository quizRepository;
    private final QuizAttemptRepository attemptRepository;
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final QuizConfigurationAccessService accessService;
    private final QuizGradingService gradingService;
    private final AttemptAnswerRepository answerRepository;
    private final AttemptAnswerOptionRepository selectedOptionRepository;

    @Transactional(readOnly = true)
    public QuizAttemptListResponse listAttempts(UUID quizId, UUID tutorId, int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUERY_PARAMETER");
        }
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "QUIZ_NOT_FOUND"));
        accessService.requireOwningTutor(quiz.getActivity(), tutorId);

        Page<QuizAttempt> result = attemptRepository.findByQuiz_Id(quizId,
                PageRequest.of(page - 1, size, Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"))));
        List<QuizAttemptListResponse.Item> items = result.getContent().stream()
                .map(attempt -> new QuizAttemptListResponse.Item(
                        attempt.getId(), attempt.getClassMember().getStudentId(), attempt.getAttemptNumber(),
                        attempt.getStatus(), attempt.getStartedAt(), attempt.getSubmittedAt(),
                        attempt.getAutoSubmitted(), attempt.getScore()))
                .toList();
        return new QuizAttemptListResponse(items, result.getNumber() + 1, result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public StartResult startOrResume(UUID quizId, UUID studentId) {
        Quiz quiz = quizRepository.findLockedById(quizId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "QUIZ_NOT_FOUND"));
        Activity activity = quiz.getActivity();
        ClassMember member = accessService.requireEligibleStudent(activity, studentId);
        OffsetDateTime now = OffsetDateTime.now();

        List<QuizAttempt> attempts = attemptRepository.findByQuiz_IdAndClassMember_ClassMemberIdOrderByAttemptNumber(
                quizId, member.getClassMemberId());
        for (QuizAttempt attempt : attempts) {
            if (!"IN_PROGRESS".equals(attempt.getStatus())) continue;
            OffsetDateTime endAt = calculateEndAt(attempt.getStartedAt(), quiz);
            if (hasReachedEndAt(endAt, now)) {
                // The expiry worker will submit it; don't perform scheduler work in this request.
                throw new ResponseStatusException(HttpStatus.CONFLICT, "QUIZ_NOT_AVAILABLE");
            }
            return new StartResult(HttpStatus.OK, toResponse(attempt, endAt, now));
        }

        if ((quiz.getOpeningTime() != null && now.isBefore(quiz.getOpeningTime()))
                || !now.isBefore(quiz.getDeadline())
                || !hasPublishedQuestions(quiz)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "QUIZ_NOT_AVAILABLE");
        }

        long usedAttempts = attempts.size();
        if (usedAttempts >= quiz.getAttemptsAllowed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ATTEMPT_LIMIT_REACHED");
        }

        QuizAttempt attempt = new QuizAttempt();
        attempt.setQuiz(quiz);
        attempt.setClassMember(member);
        attempt.setAttemptNumber((int) usedAttempts + 1);
        attempt.setStatus("IN_PROGRESS");
        attempt.setStartedAt(now);
        attempt.setAutoSubmitted(false);
        attempt = attemptRepository.saveAndFlush(attempt);
        return new StartResult(HttpStatus.CREATED, toResponse(attempt, calculateEndAt(now, quiz), now));
    }

    private boolean hasPublishedQuestions(Quiz quiz) {
        return !questionRepository.findByQuiz_IdOrderByOrderIndex(quiz.getId()).isEmpty();
    }

    private static OffsetDateTime calculateEndAt(OffsetDateTime startedAt, Quiz quiz) {
        OffsetDateTime timeLimitEnd = startedAt.plusMinutes(quiz.getTimeLimitMinutes());
        return timeLimitEnd.isBefore(quiz.getDeadline()) ? timeLimitEnd : quiz.getDeadline();
    }

    static boolean hasReachedEndAt(OffsetDateTime endAt, OffsetDateTime now) {
        return !now.isBefore(endAt);
    }

    private QuizAttemptStartResponse toResponse(QuizAttempt attempt, OffsetDateTime endAt, OffsetDateTime now) {
        List<QuizAttemptStartResponse.Question> questions = questionRepository
                .findByQuiz_IdOrderByOrderIndex(attempt.getQuiz().getId()).stream()
                .map(this::toQuestionResponse).toList();
        long remainingSeconds = Math.max(0, Duration.between(now, endAt).getSeconds());
        return new QuizAttemptStartResponse(attempt.getId(), attempt.getQuiz().getId(),
                attempt.getAttemptNumber(), attempt.getStatus(), attempt.getStartedAt(), endAt,
                remainingSeconds, questions);
    }

    private QuizAttemptStartResponse.Question toQuestionResponse(Question question) {
        List<QuizAttemptStartResponse.Option> options = optionRepository
                .findByQuestion_IdOrderByOrderIndex(question.getId()).stream()
                .map(option -> new QuizAttemptStartResponse.Option(option.getId(), option.getContent(),
                        option.getOrderIndex())).toList();
        return new QuizAttemptStartResponse.Question(question.getId(), question.getQuestionType(),
                question.getContent(), question.getPointsAwarded(), question.getPointsDeducted(),
                question.getOrderIndex(), options);
    }

    @Transactional
    public void saveAnswer(UUID attemptId, UUID questionId, UUID studentId, SaveAnswerRequest request) {
        QuizAttempt attempt = requireEditableAttempt(attemptId, studentId);

        Question question = questionRepository.findByIdAndQuiz_Id(questionId, attempt.getQuiz().getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "ATTEMPT_OR_QUESTION_NOT_FOUND"));
        if (request == null || request.selectedOptionIds() == null || request.selectedOptionIds().isEmpty()) {
            throw invalidAnswer();
        }
        List<UUID> selectedIds = request.selectedOptionIds();
        Set<UUID> distinctIds = new HashSet<>(selectedIds);
        if (distinctIds.size() != selectedIds.size()
                || (("MCQ_SINGLE".equals(question.getQuestionType())
                || "TRUE_FALSE".equals(question.getQuestionType())) && selectedIds.size() != 1)) {
            throw invalidAnswer();
        }
        List<QuestionOption> options = optionRepository.findAllById(distinctIds);
        if (options.size() != distinctIds.size()
                || options.stream().anyMatch(option -> !option.getQuestion().getId().equals(questionId))) {
            throw invalidAnswer();
        }

        AttemptAnswer answer = answerRepository.findByAttempt_IdAndQuestion_Id(attemptId, questionId)
                .orElseGet(AttemptAnswer::new);
        answer.setAttempt(attempt);
        answer.setQuestion(question);
        answer.setIsCorrect(false);
        answer.setPointsEarned(BigDecimal.ZERO);
        AttemptAnswer savedAnswer = answerRepository.saveAndFlush(answer);

        List<AttemptAnswerOption> previous = selectedOptionRepository.findByAnswer_Id(savedAnswer.getId());
        if (!previous.isEmpty()) selectedOptionRepository.deleteAll(previous);
        selectedOptionRepository.flush();

        List<AttemptAnswerOption> selectedLinks = options.stream().map(option -> {
            AttemptAnswerOptionId id = new AttemptAnswerOptionId();
            id.setAnswerId(savedAnswer.getId());
            id.setOptionId(option.getId());
            AttemptAnswerOption link = new AttemptAnswerOption();
            link.setId(id);
            link.setAnswer(savedAnswer);
            link.setOption(option);
            return link;
        }).toList();
        selectedOptionRepository.saveAll(selectedLinks);
    }

    @Transactional
    public void clearAnswer(UUID attemptId, UUID questionId, UUID studentId) {
        QuizAttempt attempt = requireEditableAttempt(attemptId, studentId);
        if (questionRepository.findByIdAndQuiz_Id(questionId, attempt.getQuiz().getId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ATTEMPT_OR_QUESTION_NOT_FOUND");
        }
        answerRepository.findByAttempt_IdAndQuestion_Id(attemptId, questionId)
                .ifPresent(answerRepository::delete);
    }

    @Transactional
    public QuizAttemptResultResponse submit(UUID attemptId, UUID studentId) {
        QuizAttempt attempt = lockOwnedStudentAttempt(attemptId, studentId, "ATTEMPT_NOT_FOUND");
        accessService.requireEligibleStudent(attempt.getQuiz().getActivity(), studentId);
        if (!"SUBMITTED".equals(attempt.getStatus())) {
            OffsetDateTime now = OffsetDateTime.now();
            OffsetDateTime endAt = calculateEndAt(attempt.getStartedAt(), attempt.getQuiz());
            attempt.setAutoSubmitted(!now.isBefore(endAt));
            attempt.setStatus("SUBMITTED");
            attempt.setSubmittedAt(now);
            gradingService.gradeAttempt(attempt);
        }
        return toResultResponse(attempt);
    }

    @Transactional(readOnly = true)
    public QuizAttemptResultResponse getResult(UUID attemptId, UUID userId) {
        QuizAttempt attempt = findVisibleAttempt(attemptId, userId);
        if (!"SUBMITTED".equals(attempt.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ATTEMPT_NOT_SUBMITTED");
        }
        return toResultResponse(attempt);
    }

    @Transactional(readOnly = true)
    public QuizAttemptStateResponse getAttemptState(UUID attemptId, UUID userId) {
        QuizAttempt attempt = findVisibleAttempt(attemptId, userId);

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime endAt = calculateEndAt(attempt.getStartedAt(), attempt.getQuiz());
        long remainingSeconds = "IN_PROGRESS".equals(attempt.getStatus())
                ? Math.max(0, Duration.between(now, endAt).getSeconds()) : 0;

        var answers = answerRepository.findByAttempt_Id(attemptId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        answer -> answer.getQuestion().getId(),
                        answer -> selectedOptionRepository.findByAnswer_Id(answer.getId()).stream()
                                .map(link -> link.getOption().getId()).toList()));
        List<QuizAttemptStateResponse.Question> questions = questionRepository
                .findByQuiz_IdOrderByOrderIndex(attempt.getQuiz().getId()).stream()
                .map(question -> {
                    List<QuizAttemptStateResponse.Option> options = optionRepository
                            .findByQuestion_IdOrderByOrderIndex(question.getId()).stream()
                            .map(option -> new QuizAttemptStateResponse.Option(option.getId(),
                                    option.getContent(), option.getOrderIndex())).toList();
                    return new QuizAttemptStateResponse.Question(question.getId(), question.getQuestionType(),
                            question.getContent(), question.getPointsAwarded(), question.getPointsDeducted(),
                            question.getOrderIndex(), options,
                            answers.getOrDefault(question.getId(), List.of()));
                }).toList();

        return new QuizAttemptStateResponse(attempt.getId(), attempt.getQuiz().getId(),
                attempt.getAttemptNumber(), attempt.getStatus(), attempt.getStartedAt(), endAt,
                remainingSeconds, questions);
    }

    private QuizAttempt findVisibleAttempt(UUID attemptId, UUID userId) {
        QuizAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ATTEMPT_NOT_FOUND"));
        if (attempt.getClassMember().getStudentId().equals(userId)) {
            accessService.requireEligibleStudent(attempt.getQuiz().getActivity(), userId);
        } else {
            accessService.requireOwningTutor(attempt.getQuiz().getActivity(), userId);
        }
        return attempt;
    }

    private QuizAttemptResultResponse toResultResponse(QuizAttempt attempt) {
        BigDecimal requiredScore = attempt.getQuiz().getRequiredScore();
        BigDecimal finalScore = gradingService.calculateFinalGrade(attempt);
        Boolean passed = requiredScore == null || finalScore == null
                ? null : finalScore.compareTo(requiredScore) >= 0;
        return new QuizAttemptResultResponse(attempt.getId(), attempt.getStatus(), attempt.getAutoSubmitted(),
                attempt.getScore(), requiredScore, passed, attempt.getSubmittedAt());
    }

    private QuizAttempt lockOwnedStudentAttempt(UUID attemptId, UUID studentId, String notFoundCode) {
        QuizAttempt attempt = findLockedAttemptAndQuiz(attemptId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, notFoundCode));
        if (!attempt.getClassMember().getStudentId().equals(studentId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        return attempt;
    }

    private Optional<QuizAttempt> findLockedAttemptAndQuiz(UUID attemptId) {
        Optional<UUID> quizId = attemptRepository.findQuizIdByAttemptId(attemptId);
        if (quizId.isEmpty() || quizRepository.findLockedById(quizId.get()).isEmpty()) {
            return Optional.empty();
        }
        return attemptRepository.findLockedById(attemptId);
    }

    private QuizAttempt requireEditableAttempt(UUID attemptId, UUID studentId) {
        QuizAttempt attempt = lockOwnedStudentAttempt(
                attemptId, studentId, "ATTEMPT_OR_QUESTION_NOT_FOUND");
        if (!"IN_PROGRESS".equals(attempt.getStatus())) throw expiredAttempt();
        OffsetDateTime endAt = calculateEndAt(attempt.getStartedAt(), attempt.getQuiz());
        if (hasReachedEndAt(endAt, OffsetDateTime.now())) throw expiredAttempt();
        accessService.requireEligibleStudent(attempt.getQuiz().getActivity(), studentId);
        return attempt;
    }

    private ResponseStatusException invalidAnswer() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ANSWER");
    }

    private ResponseStatusException expiredAttempt() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "ATTEMPT_ALREADY_SUBMITTED_OR_EXPIRED");
    }

    public record StartResult(HttpStatus status, QuizAttemptStartResponse body) {}

    @Transactional
    public void expireIfDue(UUID attemptId) {
        QuizAttempt attempt = findLockedAttemptAndQuiz(attemptId).orElse(null);
        if (attempt == null || !"IN_PROGRESS".equals(attempt.getStatus())) return;

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime endAt = calculateEndAt(attempt.getStartedAt(), attempt.getQuiz());
        if (!hasReachedEndAt(endAt, now)) return;

        attempt.setStatus("SUBMITTED");
        attempt.setSubmittedAt(now);
        attempt.setAutoSubmitted(true);
        gradingService.gradeAttempt(attempt);
    }
}


