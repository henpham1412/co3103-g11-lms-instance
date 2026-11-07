package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record QuestionResponse(
        @JsonProperty("question_id") UUID questionId,
        @JsonProperty("question_type") String questionType,
        String content,
        @JsonProperty("points_awarded") BigDecimal pointsAwarded,
        @JsonProperty("points_deducted") BigDecimal pointsDeducted,
        @JsonProperty("order_index") Integer orderIndex,
        List<Option> options
) {
    public record Option(
            @JsonProperty("option_id") UUID optionId,
            String content,
            @JsonProperty("is_correct") Boolean isCorrect,
            @JsonProperty("order_index") Integer orderIndex
    ) {}
}