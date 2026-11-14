package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AssignmentConfigurationResponse(
        @JsonProperty("assignment_id") UUID assignmentId,
        @JsonProperty("activity_id") UUID activityId,
        @JsonProperty("opening_time") OffsetDateTime openingTime,
        OffsetDateTime deadline,
        @JsonProperty("allow_late") boolean allowLate,
        @JsonProperty("allowed_file_types") List<String> allowedFileTypes,
        @JsonProperty("max_file_size_mb") Integer maxFileSizeMb,
        List<Attachment> attachments
) {
    public record Attachment(
            @JsonProperty("attachment_id") UUID attachmentId,
            @JsonProperty("file_name") String fileName,
            @JsonProperty("mime_type") String mimeType,
            @JsonProperty("size_bytes") long sizeBytes
    ) { }
}
