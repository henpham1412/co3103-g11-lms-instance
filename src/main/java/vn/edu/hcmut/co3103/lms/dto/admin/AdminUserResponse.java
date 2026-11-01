package vn.edu.hcmut.co3103.lms.dto.admin;

import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;

import java.time.Instant;
import java.util.UUID;

public record AdminUserResponse(
        UUID userId,
        LmsRole role,
        AccessStatus accessStatus,
        Instant createdAt,
        Instant updatedAt) {
}
