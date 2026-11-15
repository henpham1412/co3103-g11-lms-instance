package vn.edu.hcmut.co3103.lms.service.adaptive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import vn.edu.hcmut.co3103.lms.enums.MasteryLevel;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasteryCalculatorTest {

    private final MasteryCalculator calculator = new MasteryCalculator();

    @ParameterizedTest
    @CsvSource({"0, 0", "5, 0.00", "-5, 0"})
    void zeroDenominatorProducesNoTopicEvidence(String earned, String possible) {
        assertTrue(calculator.calculateTopicScore(decimal(earned), decimal(possible)).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"-3, 10, 0.00", "15, 10, 100.00", "0, 10, 0.00", "10, 10, 100.00"})
    void topicScoresAreClampedToInclusiveRange(String earned, String possible, String expected) {
        assertEquals(decimal(expected), calculator.calculateTopicScore(
                decimal(earned), decimal(possible)).orElseThrow());
    }

    @Test
    void unansweredMappedQuestionRemainsInDenominator() {
        // One answered 2-point question and one unanswered 3-point question.
        BigDecimal earned = decimal("2").add(BigDecimal.ZERO);
        BigDecimal possible = decimal("2").add(decimal("3"));
        assertEquals(decimal("40.00"), calculator.calculateTopicScore(earned, possible).orElseThrow());
    }

    @Test
    void usesCanonicalWeightedPointsIncludingDeductions() {
        // Correct: +6/6; incorrect: -2/3; unanswered: 0/1.
        BigDecimal earned = decimal("6").subtract(decimal("2"));
        BigDecimal possible = decimal("6").add(decimal("3")).add(decimal("1"));
        assertEquals(decimal("40.00"), calculator.calculateTopicScore(earned, possible).orElseThrow());
    }

    @ParameterizedTest
    @CsvSource({"1, 3, 33.33", "2, 3, 66.67", "1.2345, 10, 12.35", "1.2344, 10, 12.34"})
    void topicPercentageUsesTwoDecimalHalfUpRounding(String earned, String possible, String expected) {
        assertEquals(decimal(expected), calculator.calculateTopicScore(
                decimal(earned), decimal(possible)).orElseThrow());
    }

    @Test
    void topicDivisionDoesNotRoundTwiceAtHalfUpBoundary() {
        assertEquals(decimal("12.34"), calculator.calculateTopicScore(
                decimal("0.12344999999999999999999999999999999999999"), BigDecimal.ONE).orElseThrow());
    }

    @Test
    void noEvidenceDoesNotProduceWeakMastery() {
        assertTrue(calculator.averageComponent(List.of()).isEmpty());
        assertTrue(calculator.calculateMastery(List.of(), List.of()).isEmpty());
    }

    @Test
    void genuineZeroScoreProducesWeakMastery() {
        var result = calculator.calculateMastery(scores("0"), List.of()).orElseThrow();
        assertScore("0", result.score());
        assertEquals(MasteryLevel.WEAK, result.level());
    }

    @ParameterizedTest
    @CsvSource({"42, 42", "40;80, 60", "30;60;90, 60"})
    void quizOnlyUsesOneTwoOrThreeSuppliedResults(String supplied, String expected) {
        var result = calculator.calculateMastery(scores(supplied), List.of()).orElseThrow();
        assertScore(expected, result.score());
    }

    @ParameterizedTest
    @CsvSource({"90, 90", "40;80, 60", "30;60;90, 60"})
    void assignmentOnlyUsesOneTwoOrThreeSuppliedResults(String supplied, String expected) {
        var result = calculator.calculateMastery(List.of(), scores(supplied)).orElseThrow();
        assertScore(expected, result.score());
    }

    @ParameterizedTest
    @CsvSource({"20, 100, 60", "20;40, 80;100, 60", "0;30;60, 60;90;90, 55", "20;20;20, 100, 60"})
    void combinesComponentMeansWithEqualWeightRegardlessOfCounts(String quiz, String assignment,
                                                                 String expected) {
        var result = calculator.calculateMastery(scores(quiz), scores(assignment)).orElseThrow();
        assertScore(expected, result.score());
        assertEquals(MasteryLevel.DEVELOPING, result.level());
    }

    @ParameterizedTest
    @CsvSource({"49.99, WEAK", "50, DEVELOPING", "79.99, DEVELOPING", "80, SUFFICIENT"})
    void classifiesDocumentedBoundaries(String score, MasteryLevel expected) {
        assertEquals(expected, calculator.calculateMastery(scores(score), List.of()).orElseThrow().level());
    }

    @ParameterizedTest
    @CsvSource({"49.99;50, 49.995, WEAK", "79.99;80, 79.995, DEVELOPING"})
    void componentPrecisionIsRetainedForClassification(String supplied, String expected,
                                                       MasteryLevel level) {
        var result = calculator.calculateMastery(scores(supplied), List.of()).orElseThrow();
        assertScore(expected, result.score());
        assertEquals(level, result.level());
    }

    @ParameterizedTest
    @CsvSource({"49.99, 50, 49.995, WEAK", "79.99, 80, 79.995, DEVELOPING"})
    void combinedScoreIsNotRoundedToDisplayPrecision(String quiz, String assignment, String expected,
                                                      MasteryLevel level) {
        var result = calculator.calculateMastery(scores(quiz), scores(assignment)).orElseThrow();
        assertScore(expected, result.score());
        assertEquals(level, result.level());
    }

    @Test
    void nonTerminatingComponentAverageUsesDecimal128Precision() {
        var result = calculator.calculateMastery(scores("0;0;1"), List.of()).orElseThrow();
        assertEquals(decimal("0.3333333333333333333333333333333333"), result.score());
        assertEquals(34, result.score().precision());
        assertEquals(MasteryLevel.WEAK, result.level());
    }

    @Test
    void combiningRepeatingComponentRetainsDecimal128Precision() {
        var result = calculator.calculateMastery(scores("0;0;1"), scores("1")).orElseThrow();
        assertEquals(decimal("0.6666666666666666666666666666666666"), result.score());
    }

    @ParameterizedTest
    @CsvSource({"0;0;1, 0.3333333333333333333333333333333333",
            "0;0;2, 0.6666666666666666666666666666666667"})
    void repeatingAveragesRoundAtSignificantDigitPrecision(String supplied, String expected) {
        assertEquals(decimal(expected), calculator.averageComponent(scores(supplied)).orElseThrow());
    }

    @Test
    void rejectsMoreThanThreeResultsInsteadOfSilentlyChoosingAWindow() {
        var oversized = scores("10;20;30;40");
        assertThrows(IllegalArgumentException.class, () -> calculator.calculateMastery(oversized, List.of()));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculateMastery(List.of(), oversized));
    }

    @ParameterizedTest
    @CsvSource({"-0.01", "100.01"})
    void rejectsOutOfRangeComponentEvidence(String score) {
        assertThrows(IllegalArgumentException.class, () -> calculator.averageComponent(scores(score)));
    }

    @Test
    void rejectsNegativePossiblePoints() {
        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculateTopicScore(BigDecimal.ZERO, decimal("-1")));
    }

    @Test
    void nullInputsAreInvalidRatherThanMissingEvidence() {
        assertThrows(NullPointerException.class, () -> calculator.calculateTopicScore(null, BigDecimal.ONE));
        assertThrows(NullPointerException.class, () -> calculator.calculateTopicScore(BigDecimal.ONE, null));
        assertThrows(NullPointerException.class, () -> calculator.calculateMastery(null, List.of()));
        assertThrows(NullPointerException.class, () -> calculator.calculateMastery(List.of(), null));
        assertThrows(NullPointerException.class,
                () -> calculator.averageComponent(Arrays.asList(BigDecimal.ONE, null)));
    }

    private static List<BigDecimal> scores(String values) {
        return Arrays.stream(values.split(";")).map(MasteryCalculatorTest::decimal).toList();
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static void assertScore(String expected, BigDecimal actual) {
        assertEquals(0, decimal(expected).compareTo(actual));
    }
}
