package vn.edu.hcmut.co3103.lms.service.adaptive;

import vn.edu.hcmut.co3103.lms.enums.MasteryLevel;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure LMS-9 arithmetic; no evidence retrieval, grading, persistence or authorization.
 * Callers select up to three latest eligible results per learner/topic/assessment type.
 * Topic results use two-decimal HALF_UP rounding (provisional D-07 assumption).
 * Sums are exact; aggregation divisions use DECIMAL128 (34 significant digits,
 * HALF_EVEN). Aggregates are never reduced to a two-decimal display value.
 */
public final class MasteryCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal FIFTY = new BigDecimal("50");
    private static final BigDecimal EIGHTY = new BigDecimal("80");
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final MathContext AGGREGATION_PRECISION = MathContext.DECIMAL128;

    /**
     * Computes ROUND(CLAMP(100 * earned / possible, 0, 100), 2).
     * Both totals must cover only questions mapped to the requested topic.
     * Include every mapped question's possible points, even if unanswered; use
     * zero earned points for unanswered questions and canonical LMS-5 earned
     * points (including deductions) for answered questions. No mapping or a zero
     * denominator produces no evidence. A negative denominator is invalid input.
     */
    public Optional<BigDecimal> calculateTopicScore(BigDecimal earned, BigDecimal possible) {
        Objects.requireNonNull(earned, "earned");
        Objects.requireNonNull(possible, "possible");
        if (possible.signum() < 0) {
            throw new IllegalArgumentException("Possible points must not be negative");
        }
        if (possible.signum() == 0) {
            return Optional.empty();
        }

        // Clamp the numerator before dividing: equivalent for positive possible
        // points, and avoids an intermediate approximation/double rounding.
        BigDecimal numerator = earned.multiply(HUNDRED)
                .max(BigDecimal.ZERO).min(possible.multiply(HUNDRED));
        return Optional.of(numerator.divide(possible, 2, RoundingMode.HALF_UP));
    }

    /**
     * Averages an already selected latest-three window. Empty means no evidence;
     * an actual zero score remains evidence. Reject oversized windows rather than
     * silently selecting results without their timestamps/eligibility context.
     */
    public Optional<BigDecimal> averageComponent(List<BigDecimal> scores) {
        Objects.requireNonNull(scores, "scores");
        if (scores.size() > 3) {
            throw new IllegalArgumentException("Supply at most three eligible scores per component");
        }
        if (scores.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal score : scores) {
            Objects.requireNonNull(score, "score");
            if (score.signum() < 0 || score.compareTo(HUNDRED) > 0) {
                throw new IllegalArgumentException("Evidence scores must be within [0, 100]");
            }
            sum = sum.add(score);
        }
        return Optional.of(sum.divide(BigDecimal.valueOf(scores.size()), AGGREGATION_PRECISION));
    }

    /** Combines available components equally, regardless of their evidence counts. */
    public Optional<MasteryResult> calculateMastery(List<BigDecimal> quizScores,
                                                   List<BigDecimal> assignmentScores) {
        Optional<BigDecimal> quiz = averageComponent(quizScores);
        Optional<BigDecimal> assignment = averageComponent(assignmentScores);
        if (quiz.isEmpty() && assignment.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal score = quiz.isPresent() && assignment.isPresent()
                ? quiz.orElseThrow().add(assignment.orElseThrow()).divide(TWO, AGGREGATION_PRECISION)
                : quiz.orElseGet(assignment::orElseThrow);
        MasteryLevel level = score.compareTo(FIFTY) < 0 ? MasteryLevel.WEAK
                : score.compareTo(EIGHTY) < 0 ? MasteryLevel.DEVELOPING : MasteryLevel.SUFFICIENT;
        return Optional.of(new MasteryResult(score, level));
    }

    /** In-memory calculation value only; not a persistence or API contract. */
    public record MasteryResult(BigDecimal score, MasteryLevel level) {
    }
}
