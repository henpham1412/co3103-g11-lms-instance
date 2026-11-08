package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

public record ClassCatalogResponse(
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
            String status,
            Tutor tutor,
            @JsonProperty("active_member_count") long activeMemberCount,
            @JsonProperty("max_capacity") Integer maxCapacity
    ) {
    }

    public record Tutor(
            @JsonProperty("user_id") UUID userId,
            @JsonProperty("full_name") String fullName
    ) {
    }
}