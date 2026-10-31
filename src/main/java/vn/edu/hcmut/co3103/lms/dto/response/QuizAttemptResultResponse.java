package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record QuizAttemptResultResponse(
        @JsonProperty("attempt_id") UUID attemptId,
        String status,
        @JsonProperty("auto_submitted") Boolean autoSubmitted,
        BigDecimal score,
        @JsonProperty("required_score") BigDecimal requiredScore,
        Boolean passed,
        @JsonProperty("submitted_at") OffsetDateTime submittedAt
) {}
