package vn.edu.hcmut.co3103.lms.service.assessment;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.response.QuizAttemptStartResponse;
import vn.edu.hcmut.co3103.lms.entity.Activity;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.Question;
import vn.edu.hcmut.co3103.lms.entity.Quiz;
import vn.edu.hcmut.co3103.lms.entity.QuizAttempt;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionOptionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizAttemptRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizRepository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
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
            if (!now.isBefore(endAt)) {
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

    private OffsetDateTime calculateEndAt(OffsetDateTime startedAt, Quiz quiz) {
        OffsetDateTime timeLimitEnd = startedAt.plusMinutes(quiz.getTimeLimitMinutes());
        return timeLimitEnd.isBefore(quiz.getDeadline()) ? timeLimitEnd : quiz.getDeadline();
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

    public record StartResult(HttpStatus status, QuizAttemptStartResponse body) {}

    @Transactional
    public void expireIfDue(UUID attemptId) {
        QuizAttempt initial = attemptRepository.findById(attemptId).orElse(null);
        if (initial == null || !"IN_PROGRESS".equals(initial.getStatus())) return;

        Quiz quiz = quizRepository.findLockedById(initial.getQuiz().getId()).orElse(null);
        if (quiz == null) return;
        QuizAttempt attempt = attemptRepository.findLockedById(attemptId).orElse(null);
        if (attempt == null || !"IN_PROGRESS".equals(attempt.getStatus())) return;

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime endAt = calculateEndAt(attempt.getStartedAt(), quiz);
        if (now.isBefore(endAt)) return;

        attempt.setStatus("SUBMITTED");
        attempt.setSubmittedAt(now);
        attempt.setAutoSubmitted(true);
        gradingService.gradeAttempt(attempt);
    }
}


