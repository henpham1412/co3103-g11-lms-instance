package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record QuizConfigurationResponse(
        @JsonProperty("quiz_id") UUID quizId,
        @JsonProperty("time_limit_minutes") Integer timeLimitMinutes,
        @JsonProperty("attempts_allowed") Integer attemptsAllowed,
        @JsonProperty("grading_method") String gradingMethod,
        @JsonProperty("opening_time") OffsetDateTime openingTime,
        @JsonProperty("deadline") OffsetDateTime deadline,
        @JsonProperty("required_score") BigDecimal requiredScore
) {}
