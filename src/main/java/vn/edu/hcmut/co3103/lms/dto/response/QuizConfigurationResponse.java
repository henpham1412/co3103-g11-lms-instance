package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record QuizConfigurationResponse(
        @JsonProperty("quiz_id") UUID quizId,
        @JsonProperty("activity_id") UUID activityId,
        @JsonProperty("time_limit_minutes") Integer timeLimitMinutes,
        @JsonProperty("attempts_allowed") Integer attemptsAllowed,
        @JsonProperty("grading_method") String gradingMethod,
        @JsonProperty("opening_time") OffsetDateTime openingTime,
        @JsonProperty("deadline") OffsetDateTime deadline,
        @JsonProperty("required_score") BigDecimal requiredScore,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<QuestionResponse> questions
) {
    public QuizConfigurationResponse(UUID quizId, UUID activityId, Integer timeLimitMinutes,
                                     Integer attemptsAllowed, String gradingMethod,
                                     OffsetDateTime openingTime, OffsetDateTime deadline,
                                     BigDecimal requiredScore) {
        this(quizId, activityId, timeLimitMinutes, attemptsAllowed, gradingMethod,
                openingTime, deadline, requiredScore, null);
    }
}