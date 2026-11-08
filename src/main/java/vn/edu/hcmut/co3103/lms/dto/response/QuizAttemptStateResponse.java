package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record QuizAttemptStateResponse(
        @JsonProperty("attempt_id") UUID attemptId,
        @JsonProperty("quiz_id") UUID quizId,
        @JsonProperty("attempt_number") Integer attemptNumber,
        String status,
        @JsonProperty("started_at") OffsetDateTime startedAt,
        @JsonProperty("end_at") OffsetDateTime endAt,
        @JsonProperty("remaining_seconds") long remainingSeconds,
        List<Question> questions
) {
    public record Question(
            @JsonProperty("question_id") UUID questionId,
            @JsonProperty("question_type") String questionType,
            String content,
            @JsonProperty("points_awarded") BigDecimal pointsAwarded,
            @JsonProperty("points_deducted") BigDecimal pointsDeducted,
            @JsonProperty("order_index") Integer orderIndex,
            List<Option> options,
            @JsonProperty("selected_option_ids") List<UUID> selectedOptionIds
    ) {}

    public record Option(
            @JsonProperty("option_id") UUID optionId,
            String content,
            @JsonProperty("order_index") Integer orderIndex
    ) {}
}