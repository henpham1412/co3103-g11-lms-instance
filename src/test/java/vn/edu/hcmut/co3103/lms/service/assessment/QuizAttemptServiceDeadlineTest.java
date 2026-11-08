package vn.edu.hcmut.co3103.lms.service.assessment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.request.SaveAnswerRequest;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.Quiz;
import vn.edu.hcmut.co3103.lms.entity.QuizAttempt;
import vn.edu.hcmut.co3103.lms.repository.assessment.AttemptAnswerOptionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.AttemptAnswerRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionOptionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizAttemptRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuizAttemptServiceDeadlineTest {
    private final QuizAttemptRepository attemptRepository = mock(QuizAttemptRepository.class);
    private final QuizRepository quizRepository = mock(QuizRepository.class);
    private final QuizConfigurationAccessService accessService = mock(QuizConfigurationAccessService.class);
    private final QuizGradingService gradingService = mock(QuizGradingService.class);
    private QuizAttemptSubmissionService submissionService;
    private final UUID attemptId = UUID.randomUUID();
    private final UUID quizId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    private QuizAttemptService service;
    private Quiz quiz;
    private QuizAttempt attempt;
    private ClassMember member;

    @BeforeEach
    void setUp() {
        submissionService = new QuizAttemptSubmissionService(mock(AssessmentLocks.class),
                attemptRepository, quizRepository, accessService, gradingService);
        service = new QuizAttemptService(mock(AssessmentLocks.class),
                quizRepository,
                attemptRepository,
                mock(QuestionRepository.class),
                mock(QuestionOptionRepository.class),
                accessService,
                gradingService,
                submissionService,
                mock(AttemptAnswerRepository.class),
                mock(AttemptAnswerOptionRepository.class));

        OffsetDateTime now = OffsetDateTime.now();
        quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setTimeLimitMinutes(1);
        quiz.setDeadline(now.plusHours(1));

        member = new ClassMember(UUID.randomUUID(), studentId,
                ClassMember.MembershipStatus.ACTIVE, null, null);
        attempt = new QuizAttempt();
        attempt.setId(attemptId);
        attempt.setQuiz(quiz);
        attempt.setClassMember(member);
        attempt.setStatus("IN_PROGRESS");
        attempt.setStartedAt(now.minusMinutes(2));

        when(attemptRepository.findQuizIdByAttemptId(attemptId)).thenReturn(Optional.of(quizId));
        when(attemptRepository.findLockedById(attemptId)).thenReturn(Optional.of(attempt));
        when(quizRepository.findLockedById(quizId)).thenReturn(Optional.of(quiz));
        when(accessService.requireEligibleStudent(any(), eq(studentId))).thenReturn(member);
    }

    @Test
    void saveAnswerRejectsWhenEndTimeHasPassedEvenBeforeExpiryWorkerRuns() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.saveAnswer(attemptId, UUID.randomUUID(), studentId,
                        new SaveAnswerRequest(List.of(UUID.randomUUID()))));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
    }

    @Test
    void clearAnswerRejectsWhenEndTimeHasPassedEvenBeforeExpiryWorkerRuns() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.clearAnswer(attemptId, UUID.randomUUID(), studentId));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
    }

    @Test
    void saveLocksQuizBeforeLoadingAttemptAndDoesNotPreloadAttemptEntity() {
        assertThrows(ResponseStatusException.class,
                () -> service.saveAnswer(attemptId, UUID.randomUUID(), studentId,
                        new SaveAnswerRequest(List.of(UUID.randomUUID()))));

        var order = inOrder(attemptRepository, quizRepository);
        order.verify(attemptRepository).findQuizIdByAttemptId(attemptId);
        order.verify(quizRepository).findLockedById(quizId);
        order.verify(attemptRepository).findLockedById(attemptId);
        verify(attemptRepository, never()).findById(attemptId);
    }

    @Test
    void expiryLocksQuizBeforeLoadingAttemptAndUsesTheLockedState() {
        service.expireIfDue(attemptId);

        assertEquals("SUBMITTED", attempt.getStatus());
        var order = inOrder(attemptRepository, quizRepository);
        order.verify(attemptRepository).findQuizIdByAttemptId(attemptId);
        order.verify(quizRepository).findLockedById(quizId);
        order.verify(attemptRepository).findLockedById(attemptId);
        verify(attemptRepository, never()).findById(attemptId);
        verify(gradingService).gradeAttempt(attempt);
    }

    @Test
    void endTimeBoundaryIsExpiredAtAndAfterEndTimeButNotBeforeIt() {
        OffsetDateTime endAt = OffsetDateTime.parse("2026-10-31T10:05:00Z");

        assertFalse(QuizAttemptService.hasReachedEndAt(endAt, endAt.minusNanos(1)));
        assertTrue(QuizAttemptService.hasReachedEndAt(endAt, endAt));
        assertTrue(QuizAttemptService.hasReachedEndAt(endAt, endAt.plusNanos(1)));
    }
}
