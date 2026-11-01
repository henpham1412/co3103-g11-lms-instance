package vn.edu.hcmut.co3103.lms.dto.admin;

import jakarta.validation.constraints.NotNull;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;

public record UpdateUserStatusRequest(@NotNull(message = "Access status is required") AccessStatus status) {
}
