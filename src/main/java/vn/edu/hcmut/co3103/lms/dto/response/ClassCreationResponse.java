package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import vn.edu.hcmut.co3103.lms.entity.Class;

import java.time.Instant;
import java.util.UUID;

public record ClassCreationResponse(
        @JsonProperty("class_id")
        UUID classId,

        @JsonProperty("tutor_id")
        UUID tutorId,

        @JsonProperty("class_code")
        String classCode,

        @JsonProperty("title")
        String title,

        @JsonProperty("description")
        String description,

        @JsonProperty("prerequisites_description")
        String prerequisitesDescription,

        @JsonProperty("max_capacity")
        Integer maxCapacity,

        @JsonProperty("enrollment_policy")
        Class.EnrollmentPolicy enrollmentPolicy,

        @JsonProperty("status")
        Class.ClassStatus status,

        @JsonProperty("thumbnail_url")
        String thumbnailUrl,

        @JsonProperty("created_at")
        Instant createdAt,

        @JsonProperty("updated_at")
        Instant updatedAt
) {
}