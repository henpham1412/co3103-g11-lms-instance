package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

public record StudentClassPageResponse(
        List<Item> items,
        int page,
        int size,
        @JsonProperty("total_items") long totalItems,
        @JsonProperty("total_pages") int totalPages
) {
    public record Item(
            @JsonProperty("class_id") UUID classId,
            @JsonProperty("class_code") String classCode,
            String title,
            String description,
            @JsonProperty("prerequisites_description") String prerequisitesDescription,
            @JsonProperty("thumbnail_url") String thumbnailUrl,
            @JsonProperty("enrollment_policy") String enrollmentPolicy,
            @JsonProperty("class_status") String classStatus,
            @JsonProperty("membership_status") String membershipStatus,
            Tutor tutor,
            Progress progress
    ) { }

    public record Tutor(
            @JsonProperty("user_id") UUID userId,
            @JsonProperty("full_name") String fullName,
            String email
    ) { }

    public record Progress(
            @JsonProperty("completed_lessons") int completedLessons,
            @JsonProperty("eligible_lessons") int eligibleLessons,
            @JsonProperty("progress_percentage") int progressPercentage,
            String status
    ) { }
}
