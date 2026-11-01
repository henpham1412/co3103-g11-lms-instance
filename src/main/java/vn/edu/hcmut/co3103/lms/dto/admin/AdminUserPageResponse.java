package vn.edu.hcmut.co3103.lms.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record AdminUserPageResponse(
        @JsonProperty("items") List<AdminUserResponse> content,
        long page,
        int size,
        @JsonProperty("total_items") long totalElements,
        @JsonProperty("total_pages") int totalPages,
        boolean last) {
}
