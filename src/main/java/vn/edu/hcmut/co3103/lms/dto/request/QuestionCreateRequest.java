package vn.edu.hcmut.co3103.lms.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record QuestionCreateRequest(
        @JsonProperty("question_type") String questionType,
        String content,
        @JsonProperty("points_awarded") BigDecimal pointsAwarded,
        @JsonProperty("points_deducted") BigDecimal pointsDeducted,
        @JsonProperty("order_index") Integer orderIndex,
        List<Option> options,
        @JsonProperty("skill_topic_ids") List<UUID> skillTopicIds
) {
    public record Option(
            String content,
            @JsonProperty("is_correct") Boolean isCorrect,
            @JsonProperty("order_index") Integer orderIndex
    ) {}
}