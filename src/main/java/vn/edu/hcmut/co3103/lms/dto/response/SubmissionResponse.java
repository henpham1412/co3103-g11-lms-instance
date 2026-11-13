package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record SubmissionResponse(
        @JsonProperty("submission_id") UUID submissionId,
        @JsonProperty("assignment_id") UUID assignmentId,
        @JsonProperty("class_member_id") UUID classMemberId,
        @JsonProperty("student_id") UUID studentId,
        @JsonProperty("file_name") String fileName,
        @JsonProperty("mime_type") String mimeType,
        @JsonProperty("size_bytes") long sizeBytes,
        @JsonProperty("submitted_at") OffsetDateTime submittedAt,
        @JsonProperty("is_late") boolean late,
        String status,
        BigDecimal score,
        String feedback,
        @JsonProperty("graded_by") UUID gradedBy,
        @JsonProperty("graded_at") OffsetDateTime gradedAt,
        long version
) { }
