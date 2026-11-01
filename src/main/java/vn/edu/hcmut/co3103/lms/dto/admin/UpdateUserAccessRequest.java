package vn.edu.hcmut.co3103.lms.dto.admin;

import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;

// Null fields preserve the user's current role or access status.
public record UpdateUserAccessRequest(LmsRole role, AccessStatus accessStatus) {
}
