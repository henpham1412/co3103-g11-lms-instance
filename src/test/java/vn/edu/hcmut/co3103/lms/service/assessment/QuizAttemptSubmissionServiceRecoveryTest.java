package vn.edu.hcmut.co3103.lms.service.assessment;

import org.junit.jupiter.api.Test;
import vn.edu.hcmut.co3103.lms.entity.Quiz;
import vn.edu.hcmut.co3103.lms.entity.QuizAttempt;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizAttemptRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class QuizAttemptSubmissionServiceRecoveryTest {
    @Test
    void gradingFailureLeavesSubmissionCommittedAndTheNextScanCanGradeIt() {
        QuizAttemptRepository attemptRepository = mock(QuizAttemptRepository.class);
        QuizRepository quizRepository = mock(QuizRepository.class);
        QuizGradingService gradingService = mock(QuizGradingService.class);
        QuizAttemptSubmissionService service = new QuizAttemptSubmissionService(mock(AssessmentLocks.class),
                attemptRepository, quizRepository, mock(QuizConfigurationAccessService.class), gradingService);
        UUID attemptId = UUID.randomUUID();
        UUID quizId = UUID.randomUUID();

        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setTimeLimitMinutes(1);
        quiz.setDeadline(OffsetDateTime.now().plusHours(1));
        quiz.setRequiredScore(new BigDecimal("70.00"));
        quiz.setGradingMethod("HIGHEST");

        QuizAttempt attempt = new QuizAttempt();
        attempt.setId(attemptId);
        attempt.setQuiz(quiz);
        attempt.setAttemptNumber(1);
        attempt.setStatus("IN_PROGRESS");
        attempt.setStartedAt(OffsetDateTime.now().minusMinutes(2));
        attempt.setAutoSubmitted(false);

        when(attemptRepository.findQuizIdByAttemptId(attemptId)).thenReturn(Optional.of(quizId));
        when(quizRepository.findLockedById(quizId)).thenReturn(Optional.of(quiz));
        when(attemptRepository.findLockedById(attemptId)).thenReturn(Optional.of(attempt));
        when(gradingService.calculateFinalGrade(attempt)).thenReturn(new BigDecimal("80.00"));
        AtomicInteger gradingCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (gradingCalls.getAndIncrement() == 0) throw new IllegalStateException("temporary failure");
            attempt.setScore(new BigDecimal("80.00"));
            attempt.setGradedAt(OffsetDateTime.now());
            return null;
        }).when(gradingService).gradeAttempt(attempt);

        assertTrue(service.submitIfDueOrAlreadySubmitted(attemptId));
        assertEquals("SUBMITTED", attempt.getStatus());
        assertTrue(attempt.getAutoSubmitted());

        assertThrows(IllegalStateException.class, () -> service.gradeIfPending(attemptId));
        assertEquals("SUBMITTED", attempt.getStatus());
        assertNull(attempt.getScore());
        assertNull(attempt.getGradedAt());

        var response = service.gradeIfPending(attemptId).orElseThrow();
        assertEquals("SUBMITTED", response.status());
        assertEquals(new BigDecimal("80.00"), response.score());
        assertTrue(response.passed());
        assertNotNull(attempt.getGradedAt());
        assertEquals(2, gradingCalls.get());
    }
}
