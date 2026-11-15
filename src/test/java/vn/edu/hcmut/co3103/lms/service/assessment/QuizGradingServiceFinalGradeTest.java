package vn.edu.hcmut.co3103.lms.service.assessment;

import org.junit.jupiter.api.Test;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.Quiz;
import vn.edu.hcmut.co3103.lms.entity.QuizAttempt;
import vn.edu.hcmut.co3103.lms.repository.assessment.AttemptAnswerOptionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.AttemptAnswerRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionOptionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizAttemptRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuizGradingServiceFinalGradeTest {
    @Test
    void highestUsesTheMaximumSubmittedAttemptScore() {
        assertScore("80.00", QuizGradingService.aggregateFinalGrade(
                "HIGHEST", List.of(score("40.00"), score("80.00"))));
    }

    @Test
    void averageUsesTheMeanOfSubmittedAttemptScores() {
        assertScore("60.00", QuizGradingService.aggregateFinalGrade(
                "AVERAGE", List.of(score("40.00"), score("80.00"))));
    }

    @Test
    void lastUsesTheMostRecentSubmittedAttemptScore() {
        assertScore("40.00", QuizGradingService.aggregateFinalGrade(
                "LAST", List.of(score("80.00"), score("40.00"))));
    }

    @Test
    void averageRoundsToTwoDecimalPlaces() {
        assertScore("66.67", QuizGradingService.aggregateFinalGrade(
                "AVERAGE", List.of(score("50.00"), score("83.33"), score("66.67"))));
    }

    @Test
    void finalGradeRemainsPendingWhileAnySubmittedAttemptIsUngraded() {
        QuizAttemptRepository attemptRepository = mock(QuizAttemptRepository.class);
        Quiz quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        quiz.setGradingMethod("HIGHEST");
        ClassMember member = new ClassMember(UUID.randomUUID(), UUID.randomUUID(),
                ClassMember.MembershipStatus.ACTIVE, null, null);
        QuizAttempt gradedAttempt = submittedAttempt(quiz, member, score("80.00"));
        gradedAttempt.setGradedAt(java.time.OffsetDateTime.now());
        QuizAttempt ungradedAttempt = submittedAttempt(quiz, member, null);
        QuizGradingService service = new QuizGradingService(attemptRepository,
                mock(QuestionRepository.class), mock(QuestionOptionRepository.class),
                mock(AttemptAnswerRepository.class), mock(AttemptAnswerOptionRepository.class),
                mock(vn.edu.hcmut.co3103.lms.service.adaptive.MasteryRecalculationService.class),
                mock(AssessmentLocks.class), mock(vn.edu.hcmut.co3103.lms.repository.assessment.QuizRepository.class),
                mock(jakarta.persistence.EntityManager.class));
        when(attemptRepository.findByQuiz_IdAndClassMember_ClassMemberIdOrderByAttemptNumber(
                quiz.getId(), member.getClassMemberId())).thenReturn(List.of(gradedAttempt, ungradedAttempt));

        assertNull(service.calculateFinalGrade(gradedAttempt));
    }

    private QuizAttempt submittedAttempt(Quiz quiz, ClassMember member, BigDecimal score) {
        QuizAttempt attempt = new QuizAttempt();
        attempt.setQuiz(quiz);
        attempt.setClassMember(member);
        attempt.setStatus("SUBMITTED");
        attempt.setScore(score);
        return attempt;
    }

    private BigDecimal score(String value) {
        return new BigDecimal(value);
    }

    private void assertScore(String expected, BigDecimal actual) {
        assertEquals(0, score(expected).compareTo(actual));
    }
}
