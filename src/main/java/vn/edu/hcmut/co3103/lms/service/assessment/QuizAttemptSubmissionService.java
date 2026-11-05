package vn.edu.hcmut.co3103.lms.service.assessment;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.response.QuizAttemptResultResponse;
import vn.edu.hcmut.co3103.lms.entity.QuizAttempt;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizAttemptRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** Transaction boundaries for submitting and grading attempts independently. */
@Service
@RequiredArgsConstructor
public class QuizAttemptSubmissionService {
    private final QuizAttemptRepository attemptRepository;
    private final QuizRepository quizRepository;
    private final QuizConfigurationAccessService accessService;
    private final QuizGradingService gradingService;

    @Transactional
    public void submit(UUID attemptId, UUID studentId) {
        QuizAttempt attempt = lockOwnedStudentAttempt(attemptId, studentId);
        accessService.requireEligibleStudent(attempt.getQuiz().getActivity(), studentId);
        if ("SUBMITTED".equals(attempt.getStatus())) return;

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime endAt = QuizAttemptService.calculateEndAt(attempt.getStartedAt(), attempt.getQuiz());
        attempt.setAutoSubmitted(!now.isBefore(endAt));
        attempt.setStatus("SUBMITTED");
        attempt.setSubmittedAt(now);
    }

    @Transactional
    public boolean submitIfDueOrAlreadySubmitted(UUID attemptId) {
        Optional<QuizAttempt> found = findLockedAttemptAndQuiz(attemptId);
        if (found.isEmpty()) return false;
        QuizAttempt attempt = found.get();
        if ("SUBMITTED".equals(attempt.getStatus())) return true;
        if (!"IN_PROGRESS".equals(attempt.getStatus())) return false;

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime endAt = QuizAttemptService.calculateEndAt(attempt.getStartedAt(), attempt.getQuiz());
        if (!QuizAttemptService.hasReachedEndAt(endAt, now)) return false;

        attempt.setStatus("SUBMITTED");
        attempt.setSubmittedAt(now);
        attempt.setAutoSubmitted(true);
        return true;
    }

    @Transactional
    public Optional<QuizAttemptResultResponse> gradeIfPending(UUID attemptId) {
        Optional<QuizAttempt> found = findLockedAttemptAndQuiz(attemptId);
        if (found.isEmpty()) return Optional.empty();
        QuizAttempt attempt = found.get();
        if (!"SUBMITTED".equals(attempt.getStatus())) return Optional.empty();

        if (attempt.getScore() == null || attempt.getGradedAt() == null) {
            gradingService.gradeAttempt(attempt);
        }
        return Optional.of(toResultResponse(attempt));
    }

    private QuizAttempt lockOwnedStudentAttempt(UUID attemptId, UUID studentId) {
        QuizAttempt attempt = findLockedAttemptAndQuiz(attemptId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ATTEMPT_NOT_FOUND"));
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

    private QuizAttemptResultResponse toResultResponse(QuizAttempt attempt) {
        BigDecimal requiredScore = attempt.getQuiz().getRequiredScore();
        boolean graded = attempt.getScore() != null && attempt.getGradedAt() != null;
        BigDecimal finalScore = graded ? gradingService.calculateFinalGrade(attempt) : null;
        Boolean passed = !graded || requiredScore == null || finalScore == null
                ? null : finalScore.compareTo(requiredScore) >= 0;
        return new QuizAttemptResultResponse(attempt.getId(), attempt.getStatus(), attempt.getAutoSubmitted(),
                attempt.getScore(), requiredScore, passed, attempt.getSubmittedAt());
    }
}
