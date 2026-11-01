package vn.edu.hcmut.co3103.lms.dto.admin;

import jakarta.validation.constraints.NotNull;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;

public record UpdateUserRoleRequest(@NotNull(message = "Role is required") LmsRole role) {
}
