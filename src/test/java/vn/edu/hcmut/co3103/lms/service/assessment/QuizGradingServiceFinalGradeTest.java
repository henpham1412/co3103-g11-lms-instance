package vn.edu.hcmut.co3103.lms.service.assessment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private BigDecimal score(String value) {
        return new BigDecimal(value);
    }

    private void assertScore(String expected, BigDecimal actual) {
        assertEquals(0, score(expected).compareTo(actual));
    }
}
