package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record QuizAttemptListResponse(
        List<Item> items,
        int page,
        int size,
        @JsonProperty("total_items") long totalItems,
        @JsonProperty("total_pages") int totalPages
) {
    public record Item(
            @JsonProperty("attempt_id") UUID attemptId,
            @JsonProperty("student_id") UUID studentId,
            @JsonProperty("attempt_number") Integer attemptNumber,
            String status,
            @JsonProperty("started_at") OffsetDateTime startedAt,
            @JsonProperty("submitted_at") OffsetDateTime submittedAt,
            @JsonProperty("auto_submitted") Boolean autoSubmitted,
            BigDecimal score
    ) {}
}
