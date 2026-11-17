package vn.edu.hcmut.co3103.lms.service.assessment;

import java.math.BigDecimal;
import java.util.Set;

/** Shared definition rules; individual option identity/order validation stays in LMS-5. */
public final class QuizQuestionRules {
    private QuizQuestionRules() { }

    public static boolean validDefinition(String type, String content, BigDecimal awarded,
                                          BigDecimal deducted, long optionCount, long correctCount) {
        return type != null && Set.of("MCQ_SINGLE", "MCQ_MULTIPLE", "TRUE_FALSE").contains(type)
                && content != null && !content.isBlank() && representablePoints(awarded)
                && representablePoints(deducted) && optionCount >= 2 && correctCount > 0
                && (!"TRUE_FALSE".equals(type) || optionCount == 2)
                && ("MCQ_MULTIPLE".equals(type) || correctCount == 1);
    }

    private static boolean representablePoints(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.stripTrailingZeros().scale() <= 2
                && value.compareTo(new BigDecimal("99999999.99")) <= 0;
    }
}
