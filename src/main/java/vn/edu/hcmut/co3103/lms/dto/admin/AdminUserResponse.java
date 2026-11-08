package vn.edu.hcmut.co3103.lms.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;

import java.time.Instant;
import java.util.UUID;

public record AdminUserResponse(
        @JsonProperty("user_id") UUID userId,
        @JsonProperty("lms_role") LmsRole role,
        @JsonProperty("access_status") AccessStatus accessStatus,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("full_name") String fullName,
        String email,
        @JsonProperty("last_login_at") Instant lastLoginAt) {
}
