package vn.edu.hcmut.co3103.lms.service.assessment;

import org.junit.jupiter.api.Test;
import vn.edu.hcmut.co3103.lms.dto.response.QuizAttemptResultResponse;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.Quiz;
import vn.edu.hcmut.co3103.lms.entity.QuizAttempt;
import vn.edu.hcmut.co3103.lms.repository.assessment.AttemptAnswerOptionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.AttemptAnswerRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionOptionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizAttemptRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuizAttemptResultFinalGradeTest {
    @Test
    void passedUsesFinalQuizGradeInsteadOfViewedAttemptScore() {
        QuizAttemptRepository attemptRepository = mock(QuizAttemptRepository.class);
        QuizGradingService gradingService = mock(QuizGradingService.class);
        QuizConfigurationAccessService accessService = mock(QuizConfigurationAccessService.class);
        UUID attemptId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();

        Quiz quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        quiz.setGradingMethod("AVERAGE");
        quiz.setRequiredScore(new BigDecimal("70.00"));

        QuizAttempt attempt = new QuizAttempt();
        attempt.setId(attemptId);
        attempt.setQuiz(quiz);
        attempt.setClassMember(new ClassMember(UUID.randomUUID(), studentId,
                ClassMember.MembershipStatus.ACTIVE, null, null));
        attempt.setStatus("SUBMITTED");
        attempt.setAutoSubmitted(false);
        attempt.setScore(new BigDecimal("80.00"));
        attempt.setSubmittedAt(OffsetDateTime.parse("2026-10-31T10:00:00Z"));

        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(gradingService.calculateFinalGrade(attempt)).thenReturn(new BigDecimal("60.00"));

        QuizAttemptService service = new QuizAttemptService(
                mock(QuizRepository.class),
                attemptRepository,
                mock(QuestionRepository.class),
                mock(QuestionOptionRepository.class),
                accessService,
                gradingService,
                mock(AttemptAnswerRepository.class),
                mock(AttemptAnswerOptionRepository.class));

        QuizAttemptResultResponse response = service.getResult(attemptId, studentId);

        assertEquals(0, new BigDecimal("80.00").compareTo(response.score()));
        assertFalse(response.passed());
    }
}
